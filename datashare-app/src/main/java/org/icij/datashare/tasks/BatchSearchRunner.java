package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord.State;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;

public class BatchSearchRunner implements Callable<Integer>, Monitorable, UserTask {
    private Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * max scroll size will get n results at each scroll
     * each result is binding 9 fields on an insert query
     * and max sql binding is an int(2) = 32768
     * As we do batch insert with VALUES (val1, ..., val9), (val1, ..., val9)
     * max scroll size should be < 32768 / 9 (3640)
     */
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 60000;

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final User user;
    private final BatchSearchRepository repository;
    private int totalNbBatches = 0;
    private int totalProcessed = 0;

    @Inject
    public BatchSearchRunner(Indexer indexer, BatchSearchRepository repository, PropertiesProvider propertiesProvider, @Assisted User user) {
        this.indexer = indexer;
        this.repository = repository;
        this.propertiesProvider = propertiesProvider;
        this.user = user;
    }

    @Override
    public Integer call() {
        List<BatchSearch> batchSearches = repository.getQueued();
        totalNbBatches = batchSearches.size();
        logger.info("found {} queued batch searches", batchSearches.size());
        int totalResults = 0;
        for (BatchSearch batchSearch : batchSearches) {
            totalResults += run(batchSearch);
            totalProcessed += 1;
        }
        logger.info("done {} batch searches", batchSearches.size());
        return totalResults;
    }

    public int run(String batchSearchId) {
        BatchSearch batchSearch = repository.get(batchSearchId);
        return batchSearch.state == State.QUEUED ? run(batchSearch): 0;
    }

    int run(BatchSearch batchSearch) {
        int numberOfResults = 0;
        int throttleMs = parseInt(propertiesProvider.get(BATCH_SEARCH_THROTTLE).orElse("0"));
        int maxTimeSeconds = parseInt(propertiesProvider.get(BATCH_SEARCH_MAX_TIME).orElse("100000"));
        int scrollSize = min(parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000")), MAX_SCROLL_SIZE);

        logger.info("running {} queries for batch search {} on project {} with throttle {}ms and scroll size of {}",
                batchSearch.queries.size(), batchSearch.uuid, batchSearch.project, throttleMs, scrollSize);
        repository.setState(batchSearch.uuid, State.RUNNING);
        String query = null;
        try {
            for (String s : batchSearch.queries.keySet()) {
                query = s;
                Indexer.Searcher searcher = indexer.search(batchSearch.project.getId(), Document.class).
                        with(query, batchSearch.fuzziness, batchSearch.phraseMatches).
                        withFieldValues("contentType", batchSearch.fileTypes.toArray(new String[]{})).
                        withPrefixQuery("dirname", batchSearch.paths.toArray(new String[]{})).
                        withoutSource("content").limit(scrollSize);
                List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());

                long beforeScrollLoop = DatashareTime.getInstance().currentTimeMillis();
                while (docsToProcess.size() != 0 && numberOfResults < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
                    repository.saveResults(batchSearch.uuid, query, (List<Document>) docsToProcess);
                    if (DatashareTime.getInstance().currentTimeMillis() - beforeScrollLoop < maxTimeSeconds*1000) {
                        DatashareTime.getInstance().sleep(throttleMs);
                    } else {
                        throw new TimeoutException("Batch timed out after " + maxTimeSeconds + "s");
                    }
                    numberOfResults += docsToProcess.size();
                    docsToProcess = searcher.scroll().collect(toList());
                }
            }
        } catch (ElasticsearchStatusException esEx) {
            logger.error("elasticsearch exception when running batch " + batchSearch.uuid, esEx);
            repository.setState(batchSearch.uuid, new SearchException(query,
                    stream(esEx.getSuppressed()).filter(t -> t instanceof ResponseException).findFirst().orElse(esEx)));
            return numberOfResults;
        } catch (Exception ex) {
            logger.error("error when running batch " + batchSearch.uuid, ex);
            repository.setState(batchSearch.uuid, new SearchException(query, ex));
            return numberOfResults;
        }
        repository.setState(batchSearch.uuid, State.SUCCESS);
        logger.info("done batch search {} with success", batchSearch.uuid);
        return numberOfResults;
    }

    @Override
    public double getProgressRate() {
        return totalProcessed == 0 && totalNbBatches == 0 ? 0 : (double) totalProcessed/totalNbBatches;
    }

    @Override
    public User getUser() {
        return user;
    }

    public void close() throws IOException {
        indexer.close();
        repository.close();
    }
}
