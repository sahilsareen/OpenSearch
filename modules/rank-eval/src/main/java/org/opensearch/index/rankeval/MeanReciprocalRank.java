/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.rankeval;

import org.opensearch.common.ParseField;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ConstructingObjectParser;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.search.SearchHit;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import static org.opensearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.opensearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;
import static org.opensearch.index.rankeval.EvaluationMetric.joinHitsWithRatings;

/**
 * Metric implementing Mean Reciprocal Rank (https://en.wikipedia.org/wiki/Mean_reciprocal_rank).<br>
 * By default documents with a rating equal or bigger than 1 are considered to be "relevant" for the reciprocal
 * rank calculation. This value can be changes using the relevant_rating_threshold` parameter.
 */
public class MeanReciprocalRank implements EvaluationMetric {

    public static final String NAME = "mean_reciprocal_rank";

    private static final int DEFAULT_RATING_THRESHOLD = 1;
    private static final int DEFAULT_K = 10;

    /** the search window size */
    private final int k;

    /** ratings equal or above this value will be considered relevant */
    private final int relevantRatingThreshhold;

    public MeanReciprocalRank() {
        this(DEFAULT_RATING_THRESHOLD, DEFAULT_K);
    }

    MeanReciprocalRank(StreamInput in) throws IOException {
        this.relevantRatingThreshhold = in.readVInt();
        this.k = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(this.relevantRatingThreshhold);
        out.writeVInt(this.k);
    }

    /**
     * Metric implementing Mean Reciprocal Rank (https://en.wikipedia.org/wiki/Mean_reciprocal_rank).<br>
     * @param relevantRatingThreshold the rating value that a document needs to be regarded as "relevant". Defaults to 1.
     * @param k the search window size all request use.
     */
    public MeanReciprocalRank(int relevantRatingThreshold, int k) {
        if (relevantRatingThreshold < 0) {
            throw new IllegalArgumentException("Relevant rating threshold for precision must be positive integer.");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("Window size k must be positive.");
        }
        this.k = k;
        this.relevantRatingThreshhold = relevantRatingThreshold;
    }

    int getK() {
        return this.k;
    }

    @Override
    public OptionalInt forcedSearchSize() {
        return OptionalInt.of(k);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    /**
     * Return the rating threshold above which ratings are considered to be "relevant".
     */
    public int getRelevantRatingThreshold() {
        return relevantRatingThreshhold;
    }

    /**
     * Compute ReciprocalRank based on provided relevant document IDs.
     **/
    @Override
    public EvalQueryQuality evaluate(String taskId, SearchHit[] hits, List<RatedDocument> ratedDocs) {
        List<RatedSearchHit> ratedHits = joinHitsWithRatings(hits, ratedDocs);
        int firstRelevant = -1;
        int rank = 1;
        for (RatedSearchHit hit : ratedHits) {
            OptionalInt rating = hit.getRating();
            if (rating.isPresent()) {
                if (rating.getAsInt() >= this.relevantRatingThreshhold) {
                    firstRelevant = rank;
                    break;
                }
            }
            rank++;
        }

        double reciprocalRank = (firstRelevant == -1) ? 0 : 1.0d / firstRelevant;
        EvalQueryQuality evalQueryQuality = new EvalQueryQuality(taskId, reciprocalRank);
        evalQueryQuality.setMetricDetails(new Detail(firstRelevant));
        evalQueryQuality.addHitsAndRatings(ratedHits);
        return evalQueryQuality;
    }

    private static final ParseField RELEVANT_RATING_FIELD = new ParseField("relevant_rating_threshold");
    private static final ParseField K_FIELD = new ParseField("k");
    private static final ConstructingObjectParser<MeanReciprocalRank, Void> PARSER = new ConstructingObjectParser<>("reciprocal_rank",
            args -> {
                Integer optionalThreshold = (Integer) args[0];
                Integer optionalK = (Integer) args[1];
                return new MeanReciprocalRank(optionalThreshold == null ? DEFAULT_RATING_THRESHOLD : optionalThreshold,
                        optionalK == null ? DEFAULT_K : optionalK);
            });

    static {
        PARSER.declareInt(optionalConstructorArg(), RELEVANT_RATING_FIELD);
        PARSER.declareInt(optionalConstructorArg(), K_FIELD);
    }

    public static MeanReciprocalRank fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.startObject(NAME);
        builder.field(RELEVANT_RATING_FIELD.getPreferredName(), this.relevantRatingThreshhold);
        builder.field(K_FIELD.getPreferredName(), this.k);
        builder.endObject();
        builder.endObject();
        return builder;
    }

    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MeanReciprocalRank other = (MeanReciprocalRank) obj;
        return Objects.equals(relevantRatingThreshhold, other.relevantRatingThreshhold)
                && Objects.equals(k, other.k);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(relevantRatingThreshhold, k);
    }

    public static final class Detail implements MetricDetail {

        private final int firstRelevantRank;
        private static ParseField FIRST_RELEVANT_RANK_FIELD = new ParseField("first_relevant");

        Detail(int firstRelevantRank) {
            this.firstRelevantRank = firstRelevantRank;
        }

        Detail(StreamInput in) throws IOException {
            this.firstRelevantRank = in.readVInt();
        }

        @Override
        public
        String getMetricName() {
            return NAME;
        }

        @Override
        public XContentBuilder innerToXContent(XContentBuilder builder, Params params)
                throws IOException {
            return builder.field(FIRST_RELEVANT_RANK_FIELD.getPreferredName(), firstRelevantRank);
        }

        private static final ConstructingObjectParser<Detail, Void> PARSER = new ConstructingObjectParser<>(NAME, true, args -> {
            return new Detail((Integer) args[0]);
        });

        static {
            PARSER.declareInt(constructorArg(), FIRST_RELEVANT_RANK_FIELD);
        }

        public static Detail fromXContent(XContentParser parser) {
            return PARSER.apply(parser, null);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(firstRelevantRank);
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        /**
         * the ranking of the first relevant document, or -1 if no relevant document was
         * found
         */
        public int getFirstRelevantRank() {
            return firstRelevantRank;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            MeanReciprocalRank.Detail other = (MeanReciprocalRank.Detail) obj;
            return Objects.equals(firstRelevantRank, other.firstRelevantRank);
        }

        @Override
        public int hashCode() {
            return Objects.hash(firstRelevantRank);
        }
    }
}
