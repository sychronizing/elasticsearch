/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.watcher.actions;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.core.watcher.actions.throttler.ActionThrottler;
import org.elasticsearch.xpack.core.watcher.actions.throttler.Throttler;
import org.elasticsearch.xpack.core.watcher.actions.throttler.ThrottlerField;
import org.elasticsearch.xpack.core.watcher.condition.Condition;
import org.elasticsearch.xpack.core.watcher.condition.ExecutableCondition;
import org.elasticsearch.xpack.core.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.core.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.xpack.core.watcher.transform.ExecutableTransform;
import org.elasticsearch.xpack.core.watcher.transform.Transform;
import org.elasticsearch.xpack.core.watcher.watch.Payload;
import org.elasticsearch.xpack.core.watcher.watch.WatchField;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;

public class ActionWrapper implements ToXContentObject {

    private String id;
    @Nullable
    private final ExecutableCondition condition;
    @Nullable
    private final ExecutableTransform<Transform, Transform.Result> transform;
    private final ActionThrottler throttler;
    private final ExecutableAction<? extends Action> action;

    public ActionWrapper(String id, ActionThrottler throttler,
                         @Nullable ExecutableCondition condition,
                         @Nullable ExecutableTransform<Transform, Transform.Result> transform,
                         ExecutableAction<? extends Action> action) {
        this.id = id;
        this.condition = condition;
        this.throttler = throttler;
        this.transform = transform;
        this.action = action;
    }

    public String id() {
        return id;
    }

    public ExecutableCondition condition() {
        return condition;
    }

    public ExecutableTransform<Transform, Transform.Result> transform() {
        return transform;
    }

    public Throttler throttler() {
        return throttler;
    }

    public ExecutableAction<? extends Action> action() {
        return action;
    }

    /**
     * Execute the current {@link #action()}.
     * <p>
     * This executes in the order of:
     * <ol>
     * <li>Throttling</li>
     * <li>Conditional Check</li>
     * <li>Transformation</li>
     * <li>Action</li>
     * </ol>
     *
     * @param ctx The current watch's context
     * @return Never {@code null}
     */
    public ActionWrapperResult execute(WatchExecutionContext ctx) {
        ActionWrapperResult result = ctx.actionsResults().get(id);
        if (result != null) {
            return result;
        }
        if (!ctx.skipThrottling(id)) {
            Throttler.Result throttleResult = throttler.throttle(id, ctx);
            if (throttleResult.throttle()) {
                if (throttleResult.type() == Throttler.Type.ACK) {
                    return new ActionWrapperResult(id, new Action.Result.Acknowledged(action.type(), throttleResult.reason()));
                } else {
                    return new ActionWrapperResult(id, new Action.Result.Throttled(action.type(), throttleResult.reason()));
                }
            }
        }
        Condition.Result conditionResult = null;
        if (condition != null) {
            try {
                conditionResult = condition.execute(ctx);
                if (conditionResult.met() == false) {
                    ctx.watch().status().actionStatus(id).resetAckStatus(ZonedDateTime.now(ZoneOffset.UTC));
                    return new ActionWrapperResult(id, conditionResult, null,
                                                    new Action.Result.ConditionFailed(action.type(), "condition not met. skipping"));
                }
            } catch (RuntimeException e) {
                action.logger().error(
                        (Supplier<?>) () -> new ParameterizedMessage(
                                "failed to execute action [{}/{}]. failed to execute condition", ctx.watch().id(), id), e);
                return new ActionWrapperResult(id, new Action.Result.ConditionFailed(action.type(),
                                                "condition failed. skipping: {}", e.getMessage()));
            }
        }
        Payload payload = ctx.payload();
        Transform.Result transformResult = null;
        if (transform != null) {
            try {
                transformResult = transform.execute(ctx, payload);
                if (transformResult.status() == Transform.Result.Status.FAILURE) {
                    action.logger().error("failed to execute action [{}/{}]. failed to transform payload. {}", ctx.watch().id(), id,
                            transformResult.reason());
                    String msg = "Failed to transform payload";
                    return new ActionWrapperResult(id, conditionResult, transformResult, new Action.Result.Failure(action.type(), msg));
                }
                payload = transformResult.payload();
            } catch (Exception e) {
                action.logger().error(
                        (Supplier<?>) () -> new ParameterizedMessage(
                                "failed to execute action [{}/{}]. failed to transform payload.", ctx.watch().id(), id), e);
                return new ActionWrapperResult(id, conditionResult, null, new Action.Result.FailureWithException(action.type(), e));
            }
        }
        try {
            Action.Result actionResult = action.execute(id, ctx, payload);
            return new ActionWrapperResult(id, conditionResult, transformResult, actionResult);
        } catch (Exception e) {
            action.logger().error(
                    (Supplier<?>) () -> new ParameterizedMessage("failed to execute action [{}/{}]", ctx.watch().id(), id), e);
            return new ActionWrapperResult(id, new Action.Result.FailureWithException(action.type(), e));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ActionWrapper that = (ActionWrapper) o;

        return Objects.equals(id, that.id) &&
                Objects.equals(condition, that.condition) &&
                Objects.equals(transform, that.transform) &&
                Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, condition, transform, action);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        TimeValue throttlePeriod = throttler.throttlePeriod();
        if (throttlePeriod != null) {
            builder.humanReadableField(ThrottlerField.THROTTLE_PERIOD.getPreferredName(),
                    ThrottlerField.THROTTLE_PERIOD_HUMAN.getPreferredName(), throttlePeriod);
        }
        if (condition != null) {
            builder.startObject(WatchField.CONDITION.getPreferredName())
                    .field(condition.type(), condition, params)
                    .endObject();
        }
        if (transform != null) {
            builder.startObject(Transform.TRANSFORM.getPreferredName())
                    .field(transform.type(), transform, params)
                    .endObject();
        }
        builder.field(action.type(), action, params);
        return builder.endObject();
    }

    static ActionWrapper parse(String watchId, String actionId, XContentParser parser, ActionRegistry actionRegistry, Clock clock,
                               XPackLicenseState licenseState) throws IOException {

        assert parser.currentToken() == XContentParser.Token.START_OBJECT;

        ExecutableCondition condition = null;
        ExecutableTransform<Transform, Transform.Result> transform = null;
        TimeValue throttlePeriod = null;
        ExecutableAction<? extends Action> action = null;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else {
                if (WatchField.CONDITION.match(currentFieldName, parser.getDeprecationHandler())) {
                    condition = actionRegistry.getConditionRegistry().parseExecutable(watchId, parser);
                } else if (Transform.TRANSFORM.match(currentFieldName, parser.getDeprecationHandler())) {
                    transform = actionRegistry.getTransformRegistry().parse(watchId, parser);
                } else if (ThrottlerField.THROTTLE_PERIOD.match(currentFieldName, parser.getDeprecationHandler())) {
                    throttlePeriod = timeValueMillis(parser.longValue());
                } else if (ThrottlerField.THROTTLE_PERIOD_HUMAN.match(currentFieldName, parser.getDeprecationHandler())) {
                    try {
                        throttlePeriod = WatcherDateTimeUtils.parseTimeValue(parser, ThrottlerField.THROTTLE_PERIOD_HUMAN.toString());
                    } catch (ElasticsearchParseException pe) {
                        throw new ElasticsearchParseException("could not parse action [{}/{}]. failed to parse field [{}] as time value",
                                pe, watchId, actionId, currentFieldName);
                    }
                } else {
                    // it's the type of the action
                    ActionFactory actionFactory = actionRegistry.factory(currentFieldName);
                    if (actionFactory == null) {
                        throw new ElasticsearchParseException("could not parse action [{}/{}]. unknown action type [{}]", watchId,
                                actionId, currentFieldName);
                    }
                    action = actionFactory.parseExecutable(watchId, actionId, parser);
                }
            }
        }
        if (action == null) {
            throw new ElasticsearchParseException("could not parse watch action [{}/{}]. missing action type", watchId, actionId);
        }

        ActionThrottler throttler = new ActionThrottler(clock, throttlePeriod, licenseState);
        return new ActionWrapper(actionId, throttler, condition, transform, action);
    }

}
