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

import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.computer.core.common.exception.ComputerException;
import org.apache.hugegraph.computer.core.config.Config;
import org.apache.hugegraph.computer.core.graph.edge.Edge;
import org.apache.hugegraph.computer.core.graph.id.Id;
import org.apache.hugegraph.computer.core.graph.id.IdCategory;
import org.apache.hugegraph.computer.core.graph.value.DoubleValue;
import org.apache.hugegraph.computer.core.graph.value.IdSet;
import org.apache.hugegraph.computer.core.graph.value.Value;
import org.apache.hugegraph.computer.core.graph.vertex.Vertex;
import org.apache.hugegraph.computer.core.util.IdUtil;
import org.apache.hugegraph.computer.core.util.JsonUtilExt;
import org.apache.hugegraph.computer.core.worker.Computation;
import org.apache.hugegraph.computer.core.worker.ComputationContext;
import org.apache.hugegraph.computer.core.worker.WorkerContext;
import org.apache.hugegraph.rest.SerializeException;
import org.apache.hugegraph.util.JsonUtil;
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
     * source vertex id.
     * {"id": "", "idType": ""}
     */
    // todo improve: automatic inference idType
    private Id sourceId;

    /**
     * target vertex id.
     * 1. single target: [{"id": "", "idType": ""}]
     * 2. multiple target: [{"id": "", "idType": ""}, {"id": "", "idType": ""}]
     * 3. all: []
     */
    // todo improve: automatic inference idType
    private IdSet targetIdSet; // empty when targetId is all
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
    private IdSet reachedTargets; // empty when targetId is all

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
        String sourceIdStr = config.getString(OPTION_SOURCE_ID, "");
        if (StringUtils.isBlank(sourceIdStr)) {
            throw new ComputerException("The param '%s' must not be blank", OPTION_SOURCE_ID);
        }
        VertexInputJson sourceVertex;
        try {
            sourceVertex = JsonUtil.fromJson(sourceIdStr, VertexInputJson.class);
        } catch (SerializeException e) {
            throw new ComputerException("The param '%s' is unexpected format", OPTION_SOURCE_ID);
        }
        this.sourceId = IdUtil.parseId(IdCategory.parse(sourceVertex.getIdType()),
                                       sourceVertex.getId());

        String targetIdStr = config.getString(OPTION_TARGET_ID, "");
        if (StringUtils.isBlank(targetIdStr)) {
            throw new ComputerException("The param '%s' must not be blank", OPTION_TARGET_ID);
        }
        List<VertexInputJson> targetVertices;
        try {
            targetVertices = JsonUtilExt.fromJson2List(targetIdStr, VertexInputJson.class);
        } catch (SerializeException e) {
            throw new ComputerException("The param '%s' is unexpected format", OPTION_TARGET_ID);
        }
        this.targetQuantityType = this.getQuantityType(targetVertices);
        if (this.targetQuantityType != QuantityType.ALL) {
            this.targetIdSet = new IdSet();
            for (VertexInputJson targetVertex : targetVertices) {
                targetIdSet.add(IdUtil.parseId(IdCategory.parse(targetVertex.getIdType()),
                                               targetVertex.getId()));
            }
        }

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
        if (!this.sourceId.equals(vertex.id())) {
            vertex.inactivate();
            return;
        }
        value.zeroDistance(); // source vertex

        // single target && source == target
        if (this.targetQuantityType == QuantityType.SINGLE &&
            this.sourceId.equals(this.targetIdSet.value().iterator().next())) {
            LOG.debug("source vertex {} equals target vertex {}",
                      this.sourceId, this.targetIdSet.value().iterator().next());
            vertex.inactivate();
            return;
        }

        if (vertex.numEdges() <= 0) {
            // isolated vertex
            LOG.debug("source vertex {} is isolated", this.sourceId);
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

            // target vertex finds all targets reached or nowhere to go
            if ((this.isTarget(vertex) && this.isAllTargetsReached(vertex)) ||
                vertex.numEdges() <= 0) {
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
     * get quantityType by targetId
     */
    private QuantityType getQuantityType(List<VertexInputJson> targetVertices) {
        if (targetVertices.size() == 0) {
            return QuantityType.ALL;
        } else if (targetVertices.size() == 1) {
            return QuantityType.SINGLE;
        } else {
            return QuantityType.MULTIPLE;
        }
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
        return this.targetQuantityType != QuantityType.ALL &&
               this.targetIdSet.contains(vertex.id());
    }

    /**
     * determine whether all targets reached
     */
    private boolean isAllTargetsReached(Vertex vertex) {
        if (this.targetQuantityType == QuantityType.ALL) {
            return false;
        }

        if (this.targetIdSet.size() == this.reachedTargets.size()) {
            for (Id targetId : this.targetIdSet.value()) {
                if (!this.reachedTargets.contains(targetId)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static class VertexInputJson {
        private String id;
        private String idType;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIdType() {
            return idType;
        }

        public void setIdType(String idType) {
            this.idType = idType;
        }
    }
}
