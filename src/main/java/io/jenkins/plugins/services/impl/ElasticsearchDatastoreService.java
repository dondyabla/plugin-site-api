package io.jenkins.plugins.services.impl;

import io.jenkins.plugins.commons.JsonObjectMapper;
import io.jenkins.plugins.datastore.ElasticsearchTransformer;
import io.jenkins.plugins.models.*;
import io.jenkins.plugins.services.DatastoreService;
import io.jenkins.plugins.services.SearchOptions;
import io.jenkins.plugins.services.ServiceException;
import org.apache.commons.io.FileUtils;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.bucket.nested.InternalNested;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * <p>Implementation of <code>DatastoreService</code> powered by Elasticsearch</p>
 *
 * @see io.jenkins.plugins.datastore.ElasticsearchClientFactory
 */
public class ElasticsearchDatastoreService implements DatastoreService {

  private Logger logger = LoggerFactory.getLogger(ElasticsearchDatastoreService.class);

  @Inject
  private Client esClient;

  private Categories categories;
  private  Map<String, String> labelTitleMap;

  @PostConstruct
  public void postConstruct() {
    this.categories = buildCategories();
    this.labelTitleMap = buildLabelTitleMap();
  }

  @Override
  public Plugins search(SearchOptions searchOptions) throws ServiceException {
    try {
      final SearchRequestBuilder requestBuilder = esClient.prepareSearch("plugins")
        .setFrom((searchOptions.getPage() - 1) * searchOptions.getLimit())
        .setSize(searchOptions.getLimit());
      final BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
      if (searchOptions.getQuery() != null) {
        queryBuilder.must(QueryBuilders.boolQuery()
          .should(QueryBuilders.matchQuery("title", searchOptions.getQuery()))
          .should(QueryBuilders.matchQuery("name", searchOptions.getQuery()))
          .should(QueryBuilders.nestedQuery("maintainers", QueryBuilders.matchQuery("maintainers.id", searchOptions.getQuery())))
          .should(QueryBuilders.nestedQuery("maintainers", QueryBuilders.matchQuery("maintainers.name", searchOptions.getQuery())))
          .should(QueryBuilders.matchQuery("excerpt", searchOptions.getQuery()))
          .should(QueryBuilders.termsQuery("categories", searchOptions.getQuery().toLowerCase().split(",")))
          .should(QueryBuilders.termsQuery("labels", searchOptions.getQuery().toLowerCase().split(",")))
          .should(QueryBuilders.termsQuery("requireCore", searchOptions.getQuery().toLowerCase().split(",")))
        );
      } else {
        queryBuilder.must(QueryBuilders.matchAllQuery());
      }
      if (searchOptions.hasFilters()) {
        final BoolQueryBuilder filter = QueryBuilders.boolQuery();
        if (!searchOptions.getCategories().isEmpty() && !searchOptions.getLabels().isEmpty()) {
          filter.must(
            QueryBuilders.boolQuery().should(
              QueryBuilders.termsQuery("categories", searchOptions.getCategories())
            ).should(
              QueryBuilders.termsQuery("labels", searchOptions.getLabels())
            )
          );
        } else if (!searchOptions.getCategories().isEmpty()) {
          filter.must(
            QueryBuilders.boolQuery().should(
              QueryBuilders.termsQuery("categories", searchOptions.getCategories())
            )
          );
        } else if (!searchOptions.getLabels().isEmpty()) {
          filter.must(
            QueryBuilders.boolQuery().should(
              QueryBuilders.termsQuery("labels", searchOptions.getLabels())
            )
          );
        }
        if (!searchOptions.getMaintainers().isEmpty()) {
          filter.must(
            QueryBuilders.boolQuery().should(
              QueryBuilders.nestedQuery("maintainers", QueryBuilders.matchQuery("maintainers.id", searchOptions.getMaintainers()))
            ).should(
              QueryBuilders.nestedQuery("maintainers", QueryBuilders.matchQuery("maintainers.name", searchOptions.getMaintainers()))
            )
          );
        }
        if (searchOptions.getCore() != null) {
          filter.must(QueryBuilders.termQuery("requiredCore", searchOptions.getCore()));
        }
        queryBuilder.filter(filter);
      }
      requestBuilder.setQuery(queryBuilder);
      if (searchOptions.getSortBy() != null) {
        switch (searchOptions.getSortBy()) {
          case FIRST_RELEASE:
            requestBuilder.addSort(SortBuilders.fieldSort("firstRelease").order(SortOrder.DESC));
          case INSTALLED:
            requestBuilder.addSort(SortBuilders.fieldSort("stats.currentInstalls").setNestedPath("stats").order(SortOrder.DESC));
            break;
          case NAME:
            requestBuilder.addSort(SortBuilders.fieldSort("name.raw").order(SortOrder.ASC));
            break;
          case TITLE:
            requestBuilder.addSort(SortBuilders.fieldSort("title.raw").order(SortOrder.ASC));
            break;
          case TREND:
            queryBuilder.filter(QueryBuilders.termQuery("hasNoReverseDependencies", true));
            requestBuilder.addSort(SortBuilders.fieldSort("stats.trend").setNestedPath("stats").order(SortOrder.DESC));
            break;
          case UPDATED:
            requestBuilder.addSort(SortBuilders.fieldSort("releaseTimestamp").order(SortOrder.DESC));
            break;
          default: break;
        }
      }
      final SearchResponse response = requestBuilder.execute().get();
      final long total = response.getHits().getTotalHits();
      final long pages = (total + searchOptions.getLimit() - 1) / searchOptions.getLimit();
      if (response.getHits().getHits().length == 0) {
        return new Plugins(Collections.emptyList(), searchOptions.getPage(), 0, 0, searchOptions.getLimit());
      }
      return new Plugins(
        ElasticsearchTransformer.transformHits(response.getHits()),
        searchOptions.getPage(), pages, total, searchOptions.getLimit()
      );
    } catch (Exception e) {
      logger.error("Problem executing, ES query", e);
      throw new ServiceException("Problem executing ES query", e);
    }
  }

  @Override
  public Plugin getPlugin(String name) throws ServiceException {
    try {
      final GetResponse getResponse = esClient.prepareGet("plugins", "plugins", name).execute().get();
      return getResponse.isExists() ? ElasticsearchTransformer.transformGet(getResponse, Plugin.class) : null;
    } catch (Exception e) {
        logger.error("Problem executing ES query", e);
        throw new ServiceException("Problem executing ES query", e);
    }
  }

  @Override
  public Categories getCategories() throws ServiceException {
    return categories;
  }

  private Categories buildCategories() {
    try {
      final ClassLoader cl = getClass().getClassLoader();
      final File file = new File(cl.getResource("categories.json").getFile());
      final JSONArray json = new JSONObject(FileUtils.readFileToString(file, StandardCharsets.UTF_8)).getJSONArray("categories");
      final List<Category> categories = StreamSupport.stream(json.spliterator(), false)
        .map(obj -> {
          try {
            return JsonObjectMapper.getObjectMapper().readValue(obj.toString(), Category.class);
          } catch (IOException e) {
            return null;
          }
        }).filter(category -> category != null).collect(Collectors.toList());
      return new Categories(categories);
    } catch (Exception e) {
      logger.error("Problem getting categories", e);
      throw new ServiceException("Problem getting categories", e);
    }
  }

  @Override
  public Maintainers getMaintainers() throws ServiceException {
    try {
      final SearchRequestBuilder requestBuilder = esClient.prepareSearch("plugins")
        .addAggregation(AggregationBuilders.nested("maintainers").path("maintainers")
          .subAggregation(AggregationBuilders.terms("maintainers").field("maintainers.id").size(0))
        )
        .setSize(0);
      final SearchResponse response = requestBuilder.execute().get();
      final InternalNested nested = response.getAggregations().get("maintainers");
      final StringTerms agg = nested.getAggregations().get("maintainers");
      final List<String> maintainers = agg.getBuckets().stream()
        .map(MultiBucketsAggregation.Bucket::getKeyAsString)
        .collect(Collectors.toList());
      maintainers.sort(Comparator.naturalOrder());
      return new Maintainers(maintainers);
    } catch (Exception e) {
      logger.error("Problem getting maintainers", e);
      throw new ServiceException("Problem getting maintainers", e);
    }
  }

  @Override
  public Labels getLabels() throws ServiceException {
    try {
      final SearchRequestBuilder requestBuilder = esClient.prepareSearch("plugins")
        .addAggregation(AggregationBuilders.terms("labels").field("labels").size(0))
        .setSize(0);
      final SearchResponse response = requestBuilder.execute().get();
      final StringTerms agg = response.getAggregations().get("labels");
      final List<Label> labels = agg.getBuckets().stream()
        .map(MultiBucketsAggregation.Bucket::getKeyAsString)
        .map(key -> new Label(key, labelTitleMap.getOrDefault(key, null)))
        .collect(Collectors.toList());
      return new Labels(labels);
    } catch (Exception e) {
      logger.error("Problem getting labels", e);
      throw new ServiceException("Problem getting labels", e);
    }
  }

  private Map<String, String> buildLabelTitleMap() {
    try {
      final ClassLoader cl = getClass().getClassLoader();
      final File file = new File(cl.getResource("labels.json").getFile());
      final JSONArray labels = new JSONObject(FileUtils.readFileToString(file, StandardCharsets.UTF_8)).getJSONArray("labels");
      return StreamSupport.stream(labels.spliterator(), false)
        .map(obj -> (JSONObject)obj)
        .collect(Collectors.toMap(label -> label.getString("id"), label -> label.getString("title")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Versions getVersions() throws ServiceException {
    try {
      final SearchRequestBuilder requestBuilder = esClient.prepareSearch("plugins")
        .addAggregation(AggregationBuilders.terms("versions").field("requiredCore").size(0))
        .setSize(0);
      final SearchResponse response = requestBuilder.execute().get();
      final StringTerms agg = response.getAggregations().get("versions");
      final List<String> versions = agg.getBuckets().stream()
        .map(MultiBucketsAggregation.Bucket::getKeyAsString)
        .collect(Collectors.toList());
      return new Versions(versions);
    } catch (Exception e) {
      logger.error("Problem getting versions", e);
      throw new ServiceException("Problem getting versions", e);
    }
  }
}
