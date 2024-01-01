/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.computer.algorithm.path.shortest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.computer.core.common.exception.ComputerException;
import org.apache.hugegraph.computer.core.config.Config;
import org.apache.hugegraph.computer.core.graph.edge.Edge;
import org.apache.hugegraph.computer.core.graph.id.Id;
import org.apache.hugegraph.computer.core.graph.value.DoubleValue;
import org.apache.hugegraph.computer.core.graph.value.IdSet;
import org.apache.hugegraph.computer.core.graph.value.Value;
import org.apache.hugegraph.computer.core.graph.vertex.Vertex;
import org.apache.hugegraph.computer.core.worker.Computation;
import org.apache.hugegraph.computer.core.worker.ComputationContext;
import org.apache.hugegraph.computer.core.worker.WorkerContext;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

public class SingleSourceShortestPath implements Computation<SingleSourceShortestPathValue> {

    private static final Logger LOG = Log.logger(SingleSourceShortestPath.class);

    public static final String OPTION_SOURCE_ID = "single_source_shortest_path.source_id";
    public static final String OPTION_TARGET_ID = "single_source_shortest_path.target_id";
    public static final String OPTION_WEIGHT_PROPERTY =
            "single_source_shortest_path.weight_property";
    public static final String OPTION_DEFAULT_WEIGHT =
            "single_source_shortest_path.default_weight";

    /**
     * source vertex id
     */
    private String sourceId;

    /**
     * target vertex id.
     * 1. single target: one vertex id
     * 2. multiple target: multiple vertex ids separated by comma
     * 3. all: *
     */
    private String targetId;

    /**
     * cache of targetId
     */
    private Set<String> targetIdSet;

    /**
     * target quantity type
     */
    private QuantityType targetQuantityType;

    /**
     * weight property.
     * weight value must be a positive number.
     */
    private String weightProperty;

    /**
     * default weight.
     * default 1
     */
    private Double defaultWeight;

    //****************** global data ******************//
    /**
     * reached targets
     */
    private IdSet reachedTargets;

    @Override
    public String category() {
        return "path";
    }

    @Override
    public String name() {
        return "single_source_shortest_path";
    }

    @Override
    public void init(Config config) {
        this.sourceId = config.getString(OPTION_SOURCE_ID, "");
        if (StringUtils.isBlank(this.sourceId)) {
            throw new ComputerException("The param '%s' must not be blank", OPTION_SOURCE_ID);
        }

        this.targetId = config.getString(OPTION_TARGET_ID, "");
        if (StringUtils.isBlank(this.targetId)) {
            throw new ComputerException("The param '%s' must not be blank", OPTION_TARGET_ID);
        }

        // remove spaces
        this.targetId = Arrays.stream(this.targetId.split(","))
                              .map(e -> e.trim())
                              .collect(Collectors.joining(","));
        // cache targetId
        this.targetIdSet = new HashSet<>(Arrays.asList(this.targetId.split(",")));
        this.targetQuantityType = this.getQuantityType();

        this.weightProperty = config.getString(OPTION_WEIGHT_PROPERTY, "");

        this.defaultWeight = config.getDouble(OPTION_DEFAULT_WEIGHT, 1);
        if (this.defaultWeight <= 0) {
            throw new ComputerException("The param '%s' must be greater than 0, " +
                                        "actual got '%s'",
                                        OPTION_DEFAULT_WEIGHT, this.defaultWeight);
        }
    }

    @Override
    public void compute0(ComputationContext context, Vertex vertex) {
        SingleSourceShortestPathValue value = new SingleSourceShortestPathValue();
        value.unreachable();
        vertex.value(value);

        // start from source vertex
        if (!this.idEquals(vertex, this.sourceId)) {
            vertex.inactivate();
            return;
        }
        value.zeroDistance(); // source vertex

        // single target && source vertex == target vertex
        if (this.targetQuantityType == QuantityType.SINGLE &&
            this.sourceId.equals(this.targetId)) {
            LOG.debug("source vertex {} equals target vertex {}", this.sourceId, this.targetId);
            vertex.inactivate();
            return;
        }

        if (vertex.numEdges() <= 0) {
            // isolated vertex
            LOG.debug("source vertex {} can not reach target vertex {}",
                      this.sourceId, this.targetId);
            vertex.inactivate();
            return;
        }

        vertex.edges().forEach(edge -> {
            SingleSourceShortestPathValue message = new SingleSourceShortestPathValue();
            message.addToPath(vertex, this.getEdgeWeight(edge));

            context.sendMessage(edge.targetId(), message);
        });

        vertex.inactivate();
    }

    @Override
    public void compute(ComputationContext context, Vertex vertex,
                        Iterator<SingleSourceShortestPathValue> messages) {
        if (this.isTarget(vertex) && !this.reachedTargets.contains(vertex.id())) {
            // reached targets
            this.reachedTargets.add(vertex.id());
        }

        while (messages.hasNext()) {
            SingleSourceShortestPathValue message = messages.next();
            SingleSourceShortestPathValue value = vertex.value();

            if (message.totalWeight() < value.totalWeight()) {
                // find a shorter path
                value.shorterPath(vertex, message.path(), message.totalWeight());
            } else {
                continue;
            }

            // reach all target or nowhere to go
            if (this.isAllTargetsReached(vertex) || vertex.numEdges() <= 0) {
                continue;
            }

            vertex.edges().forEach(edge -> {
                SingleSourceShortestPathValue forwardMessage = new SingleSourceShortestPathValue();
                forwardMessage.addToPath(value.path(),
                                         value.totalWeight() + this.getEdgeWeight(edge));

                context.sendMessage(edge.targetId(), forwardMessage);
            });
        }

        vertex.inactivate();
    }

    @Override
    public void beforeSuperstep(WorkerContext context) {
        this.reachedTargets = context.aggregatedValue(
                SingleSourceShortestPathMaster.SINGLE_SOURCE_SHORTEST_PATH_REACHED_TARGETS);
    }

    @Override
    public void afterSuperstep(WorkerContext context) {
        context.aggregateValue(
                SingleSourceShortestPathMaster.SINGLE_SOURCE_SHORTEST_PATH_REACHED_TARGETS,
                this.reachedTargets);
    }

    /**
     * get QuantityType by this.targetId
     */
    private QuantityType getQuantityType() {
        if (this.targetId.equals("*")) {
            return QuantityType.ALL;
        } else if (this.targetId.contains(",")) {
            return QuantityType.MULTIPLE;
        } else {
            return QuantityType.SINGLE;
        }
    }

    /**
     * determine whether vertex.id and id are equal
     */
    private boolean idEquals(Vertex vertex, String id) {
        return vertex.id().value().toString().equals(id);
    }

    /**
     * get the weight of an edge by its weight property
     */
    private double getEdgeWeight(Edge edge) {
        double weight = this.defaultWeight;

        Value property = edge.property(this.weightProperty);
        if (property != null) {
            if (!property.isNumber()) {
                throw new ComputerException("The value of %s must be a numeric value, " +
                                            "actual got '%s'",
                                            this.weightProperty, property.string());
            }

            weight = ((DoubleValue) property).doubleValue();
            if (weight <= 0) {
                throw new ComputerException("The value of %s must be greater than 0, " +
                                            "actual got '%s'",
                                            this.weightProperty, property.string());
            }
        }
        return weight;
    }

    /**
     * determine whether vertex is one of the target
     */
    private boolean isTarget(Vertex vertex) {
        return this.targetIdSet.contains(vertex.id().toString());
    }

    /**
     * determine whether all targets reached
     */
    private boolean isAllTargetsReached(Vertex vertex) {
        if (this.targetQuantityType == QuantityType.SINGLE &&
            this.idEquals(vertex, this.targetId)) {
            return true;
        }

        if (this.targetQuantityType == QuantityType.MULTIPLE) {
            if (this.targetIdSet.size() == this.reachedTargets.size()) {
                List<String> reachedTargets = this.reachedTargets.value()
                                                                 .stream().map(Id::toString)
                                                                 .collect(Collectors.toList());
                for (String targetId : this.targetIdSet) {
                    if (!reachedTargets.contains(targetId)) {
                        return false;
                    }
                }
                return true;
            }
        }

        return false;
    }
}
