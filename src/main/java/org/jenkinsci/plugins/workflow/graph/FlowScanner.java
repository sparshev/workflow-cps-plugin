package org.jenkinsci.plugins.workflow.graph;

/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

import com.google.common.base.Predicate;
import hudson.model.Action;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Generified algorithms for scanning flows for information
 * Supports a variety of algorithms for searching, and pluggable conditions
 * Worth noting: predicates may be stateful here
 *
 * ANALYSIS method will
 * @author <samvanoort@gmail.com>Sam Van Oort</samvanoort@gmail.com>
 */
public class FlowScanner {

    /**
     * Create a predicate that will match on all FlowNodes having a specific action present
     * @param actionClass Action class to look for
     * @param <T> Action type
     * @return Predicate that will match when FlowNode has the action given
     */
    @Nonnull
    public static <T extends Action>  Predicate<FlowNode> createPredicateWhereActionExists(@Nonnull final Class<T> actionClass) {
        return new Predicate<FlowNode>() {
            @Override
            public boolean apply(FlowNode input) {
                return (input != null && input.getAction(actionClass) != null);
            }
        };
    }

    // Default predicates
    static final Predicate<FlowNode> MATCH_HAS_LABEL = createPredicateWhereActionExists(LabelAction.class);
    static final Predicate<FlowNode> MATCH_IS_STAGE = createPredicateWhereActionExists(StageAction.class);
    static final Predicate<FlowNode> MATCH_HAS_WORKSPACE = createPredicateWhereActionExists(WorkspaceAction.class);
    static final Predicate<FlowNode> MATCH_HAS_ERROR = createPredicateWhereActionExists(ErrorAction.class);
    static final Predicate<FlowNode> MATCH_HAS_LOG = createPredicateWhereActionExists(LogAction.class);

    public static Predicate<FlowNode> predicateMatchStepDescriptor(final String descriptorId) {
        Predicate<FlowNode> outputPredicate = new Predicate<FlowNode>() {
            @Override
            public boolean apply(FlowNode input) {
                if (input instanceof StepAtomNode) {
                    StepAtomNode san = (StepAtomNode)input;
                    return descriptorId.equals(san.getDescriptor().getId());
                }
                return false;
            }
        };
        return outputPredicate;
    }

    /** Interface to be used for scanning/analyzing FlowGraphs with support for different visit orders
     */
    public interface ScanAlgorithm {

        /**
         * Search for first node (walking from the heads through parents) that matches the condition
         * @param heads Nodes to start searching from
         * @param stopNodes Search doesn't go beyond any of these nodes, null or empty will run to end of flow
         * @param matchPredicate Matching condition for search
         * @return First node matching condition, or null if none found
         */
        @CheckForNull
        public FlowNode findFirstMatch(@CheckForNull Collection<FlowNode> heads, @CheckForNull Collection<FlowNode> stopNodes, @Nonnull Predicate<FlowNode> matchPredicate);

        /**
         * Search for first node (walking from the heads through parents) that matches the condition
         * @param heads Nodes to start searching from
         * @param stopNodes Search doesn't go beyond any of these nodes, null or empty will run to end of flow
         * @param matchPredicate Matching condition for search
         * @return All nodes matching condition
         */
        @Nonnull
        public Collection<FlowNode> findAllMatches(@CheckForNull Collection<FlowNode> heads, @CheckForNull Collection<FlowNode> stopNodes, @Nonnull Predicate<FlowNode> matchPredicate);
    }

    /**
     * Base class for flow scanners, which offers basic methods and stubs for algorithms
     * Scanners store state internally, and are not thread-safe but are reusable
     * Scans/analysis of graphs is implemented via internal iteration to allow reusing algorithm bodies
     * However internal iteration has access to additional information
     */
    public static abstract class AbstractFlowScanner implements ScanAlgorithm {

        // State variables, not all need be used
        protected ArrayDeque<FlowNode> _queue;
        protected FlowNode _current;

        // Public APIs need to invoke this before searches
        protected abstract void initialize();

        protected abstract void setHeads(@Nonnull Collection<FlowNode> heads);

        /**
         * Actual meat of the iteration, get the next node to visit, using & updating state as needed
         * @param blackList Nodes that are not eligible for visiting
         * @return Next node to visit, or null if we've exhausted the node list
         */
        @CheckForNull
        protected abstract FlowNode next(@Nonnull Collection<FlowNode> blackList);


        /** Fast internal scan from start through single-parent (unbranched) nodes until we hit a node with one of the following:
         *      - Multiple parents
         *      - No parents
         *      - Satisfies the endCondition predicate
         *
         * @param endCondition Predicate that ends search
         * @return Node satisfying condition
         */
        @CheckForNull
        protected static FlowNode linearScanUntil(@Nonnull FlowNode start, @Nonnull Predicate<FlowNode> endCondition) {
            while(true) {
                if (endCondition.apply(start)){
                    break;
                }
                List<FlowNode> parents = start.getParents();
                if (parents == null || parents.size() == 0 || parents.size() > 1) {
                    break;
                }
                start = parents.get(0);
            }
            return start;
        }

        /** Convert stop nodes to a collection that can efficiently be checked for membership, handling nulls if needed */
        @Nonnull
        protected Collection<FlowNode> convertToFastCheckable(@CheckForNull Collection<FlowNode> nodeCollection) {
            if (nodeCollection == null || nodeCollection.size()==0) {
                return Collections.EMPTY_SET;
            } else if (nodeCollection instanceof Set) {
                return nodeCollection;
            }
            return nodeCollection.size() > 5 ? new HashSet<FlowNode>(nodeCollection) : nodeCollection;
        }

        @CheckForNull
        public FlowNode findFirstMatch(@CheckForNull Collection<FlowNode> heads, @Nonnull Predicate<FlowNode> matchPredicate) {
            return this.findFirstMatch(heads, null, matchPredicate);
        }

        @Nonnull
        public Collection<FlowNode> findAllMatches(@CheckForNull Collection<FlowNode> heads, @Nonnull Predicate<FlowNode> matchPredicate) {
            return this.findAllMatches(heads, null, matchPredicate);
        }

        // Basic algo impl
        public FlowNode findFirstMatch(@CheckForNull Collection<FlowNode> heads,
                                               @CheckForNull Collection<FlowNode> endNodes,
                                               Predicate<FlowNode> matchCondition) {
            if (heads == null || heads.size() == 0) {
                return null;
            }
            initialize();
            this.setHeads(heads);
            Collection<FlowNode> fastEndNodes = convertToFastCheckable(endNodes);

            while ((_current = next(fastEndNodes)) != null) {
                if (matchCondition.apply(_current)) {
                    return _current;
                }
            }
            return null;
        }

        // Basic algo impl
        public List<FlowNode> findAllMatches(@CheckForNull Collection<FlowNode> heads,
                                               @CheckForNull Collection<FlowNode> endNodes,
                                               Predicate<FlowNode> matchCondition) {
            if (heads == null || heads.size() == 0) {
                return null;
            }
            initialize();
            this.setHeads(heads);
            Collection<FlowNode> fastEndNodes = convertToFastCheckable(endNodes);
            ArrayList<FlowNode> nodes = new ArrayList<FlowNode>();

            while ((_current = next(fastEndNodes)) != null) {
                if (matchCondition.apply(_current)) {
                    nodes.add(_current);
                }
            }
            return nodes;
        }
    }

    /** Does a simple and efficient depth-first search */
    public static class DepthFirstScanner extends AbstractFlowScanner {

        protected HashSet<FlowNode> _visited = new HashSet<FlowNode>();

        protected void initialize() {
            if (this._queue == null) {
                this._queue = new ArrayDeque<FlowNode>();
            } else {
                this._queue.clear();
            }
            this._visited.clear();
            this._current = null;
        }

        @Override
        protected void setHeads(@Nonnull Collection<FlowNode> heads) {
            // Needs to handle blacklist
            _queue.addAll(heads);
        }

        @Override
        protected FlowNode next(@Nonnull Collection<FlowNode> blackList) {
            FlowNode output = null;
            if (_current != null) {
                List<FlowNode> parents = _current.getParents();
                if (parents != null) {
                    for (FlowNode f : parents) {
                        if (!blackList.contains(f) && !_visited.contains(f)) {
                            if (output != null ) {
                                output = f;
                            } else {
                                _queue.push(f);
                            }
                        }
                    }
                }
            }

            if (output == null && _queue.size() > 0) {
                output = _queue.pop();
            }
            _visited.add(output);
            return output;
        }
    }

    /**
     * Scans through a single ancestry, does not cover parallel branches
     */
    public static class LinearScanner extends AbstractFlowScanner {

        @Override
        protected void initialize() {
            // no-op for us
        }

        @Override
        protected void setHeads(@Nonnull Collection<FlowNode> heads) {
            if (heads.size() > 0) {
                this._current = heads.iterator().next();
            }
        }

        @Override
        protected FlowNode next(@Nonnull Collection<FlowNode> blackList) {
            if (_current == null) {
                return null;
            }
            List<FlowNode> parents = _current.getParents();
            if (parents != null || parents.size() > 0) {
                for (FlowNode f : parents) {
                    if (!blackList.contains(f)) {
                        return f;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Scanner that jumps over nested blocks
     */
    public static class BlockHoppingScanner extends AbstractFlowScanner {

        @Override
        public FlowNode findFirstMatch(@CheckForNull Collection<FlowNode> heads, @CheckForNull Collection<FlowNode> stopNodes, @Nonnull Predicate<FlowNode> matchPredicate) {
            if (heads == null || heads.size() == 0) {
                return null;
            }

            // Do what we need to for fast tests
            Collection<FlowNode> fastStopNodes = convertToFastCheckable(stopNodes);

            FlowNode current = heads.iterator().next();
            while (current != null) {
                if (!(current instanceof BlockEndNode) && matchPredicate.apply(current)) {
                    return current;
                } else { // Hop the block
                    current = ((BlockEndNode) current).getStartNode();
                }
                List<FlowNode> parents = current.getParents(); // Parents never null
                current = null;
                for (FlowNode p : parents) {
                    if (!fastStopNodes.contains(p)) {
                        current = p;
                        break;
                    }
                }
            }
            return current;
        }

        protected FlowNode jumpBlock(FlowNode current) {
            return (current instanceof BlockEndNode) ?
                ((BlockEndNode)current).getStartNode() : current;
        }

        @Override
        protected void initialize() {

        }

        @Override
        protected void setHeads(@Nonnull Collection<FlowNode> heads) {
            _queue.addAll(heads);
        }

        @Override
        protected FlowNode next(@Nonnull Collection<FlowNode> blackList) {
            if (_current == null) {
                return null;
            }
            List<FlowNode> parents = _current.getParents();
            if (parents != null || parents.size() > 0) {
                for (FlowNode f : parents) {
                    if (!blackList.contains(f)) {
                        return f;
                    }
                }
            }
            return null;
        }
    }
}
