/*
 * Copyright 2020 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.field;

import com.yelp.nrtsearch.server.doc.LoadedDocValues;
import com.yelp.nrtsearch.server.grpc.Field;
import com.yelp.nrtsearch.server.grpc.RangeQuery;
import com.yelp.nrtsearch.server.grpc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredValue;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.NumericUtils;

/** Field class for 'DOUBLE' field type. */
public class DoubleFieldDef extends NumberFieldDef<Double> {

  public DoubleFieldDef(
      String name, Field requestField, FieldDefCreator.FieldDefCreatorContext context) {
    super(name, requestField, DOUBLE_PARSER, context, Double.class);
  }

  @Override
  protected org.apache.lucene.document.Field getDocValueField(Number fieldValue) {
    if (docValuesType == DocValuesType.NUMERIC) {
      return new NumericDocValuesField(getName(), SORTED_DOUBLE_ENCODER.applyAsLong(fieldValue));
    } else if (docValuesType == DocValuesType.SORTED_NUMERIC) {
      return new SortedNumericDocValuesField(
          getName(), SORTED_DOUBLE_ENCODER.applyAsLong(fieldValue));
    }
    throw new IllegalStateException(
        String.format("Unsupported doc value type %s for field %s", docValuesType, this.getName()));
  }

  @Override
  protected org.apache.lucene.document.Field getPointField(Number fieldValue) {
    return new DoublePoint(getName(), fieldValue.doubleValue());
  }

  @Override
  protected LoadedDocValues<Double> getNumericDocValues(NumericDocValues docValues) {
    return new LoadedDocValues.SingleDouble(docValues);
  }

  @Override
  protected LoadedDocValues<Double> getSortedNumericDocValues(SortedNumericDocValues docValues) {
    return new LoadedDocValues.SortedDoubles(docValues);
  }

  @Override
  protected LongToDoubleFunction getBindingDecoder() {
    return BindingValuesSources.SORTED_DOUBLE_DECODER;
  }

  @Override
  protected SortField.Type getSortFieldType() {
    return SortField.Type.DOUBLE;
  }

  @Override
  public Object parseLastValue(String value) {
    return Double.valueOf(value);
  }

  @Override
  protected Number getSortMissingValue(boolean missingLast) {
    return missingLast ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
  }

  @Override
  public SearchResponse.Hit.FieldValue getStoredFieldValue(StoredValue value) {
    return SearchResponse.Hit.FieldValue.newBuilder()
        .setDoubleValue(value.getDoubleValue())
        .build();
  }

  @Override
  public String getType() {
    return "DOUBLE";
  }

  @Override
  public Query getRangeQuery(RangeQuery rangeQuery) {
    verifySearchableOrDocValues("Range query");
    double lower =
        rangeQuery.getLower().isEmpty()
            ? Double.NEGATIVE_INFINITY
            : Double.parseDouble(rangeQuery.getLower());
    double upper =
        rangeQuery.getUpper().isEmpty()
            ? Double.POSITIVE_INFINITY
            : Double.parseDouble(rangeQuery.getUpper());
    if (rangeQuery.getLowerExclusive()) {
      lower = DoublePoint.nextUp(lower);
    }
    if (rangeQuery.getUpperExclusive()) {
      upper = DoublePoint.nextDown(upper);
    }
    ensureUpperIsMoreThanLower(rangeQuery, lower, upper);

    Query pointQuery = null;
    Query dvQuery = null;
    if (isSearchable()) {
      pointQuery = DoublePoint.newRangeQuery(rangeQuery.getField(), lower, upper);
      if (!hasDocValues()) {
        return pointQuery;
      }
    }
    if (hasDocValues()) {
      dvQuery =
          SortedNumericDocValuesField.newSlowRangeQuery(
              rangeQuery.getField(),
              NumericUtils.doubleToSortableLong(lower),
              NumericUtils.doubleToSortableLong(upper));
      if (!isSearchable()) {
        return dvQuery;
      }
    }
    return new IndexOrDocValuesQuery(pointQuery, dvQuery);
  }

  private void ensureUpperIsMoreThanLower(RangeQuery rangeQuery, double lower, double upper) {
    if (lower > upper) {
      throw new IllegalArgumentException(
          "Lower value is higher than upper value for RangeQuery: " + rangeQuery);
    }
  }

  @Override
  public Query getTermQueryFromDoubleValue(double doubleValue) {
    verifySearchable("Term query");
    return DoublePoint.newExactQuery(getName(), doubleValue);
  }

  @Override
  public Query getTermInSetQueryFromDoubleValues(List<Double> doubleValues) {
    verifySearchable("Term in set query");
    return DoublePoint.newSetQuery(getName(), doubleValues);
  }

  @Override
  public Query getTermQueryFromTextValue(String textValue) {
    return getTermQueryFromDoubleValue(Double.parseDouble(textValue));
  }

  @Override
  public Query getTermInSetQueryFromTextValues(List<String> textValues) {
    List<Double> doubleTerms = new ArrayList<>(textValues.size());
    textValues.forEach((s) -> doubleTerms.add(Double.parseDouble(s)));
    return getTermInSetQueryFromDoubleValues(doubleTerms);
  }
}
