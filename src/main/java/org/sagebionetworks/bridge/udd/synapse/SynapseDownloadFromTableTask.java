package org.sagebionetworks.bridge.udd.synapse;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import au.com.bytecode.opencsv.CSVWriter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.file.BulkFileDownloadResponse;
import org.sagebionetworks.repo.model.file.FileDownloadSummary;
import org.sagebionetworks.util.csv.CsvNullReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.udd.dynamodb.UploadSchema;
import org.sagebionetworks.bridge.udd.exceptions.AsyncTaskExecutionException;
import org.sagebionetworks.bridge.udd.exceptions.AsyncTimeoutException;
import org.sagebionetworks.bridge.udd.helper.FileHelper;

/**
 * A one-shot asynchronous task to query a Synapse table and download the CSV. This task returns the list of files
 * downloaded. This includes the CSV (if the query pulls data from the table) and a ZIP with the attached file handles
 * (if there are any).
 */
public class SynapseDownloadFromTableTask implements Callable<List<File>> {
    private static final Logger LOG = LoggerFactory.getLogger(SynapseDownloadFromTableTask.class);

    private static final String ERROR_DOWNLOADING_ATTACHMENT = "Error downloading attachment";
    private static final String QUERY_TEMPLATE =
            "SELECT * FROM %s WHERE healthCode = '%s' AND uploadDate >= '%s' AND uploadDate <= '%s'";

    // Task parameters. Params is passed in by constructor. Context is created by this task.
    private final SynapseDownloadFromTableParameters param;
    private final SynapseDownloadFromTableContext ctx = new SynapseDownloadFromTableContext();

    // Helpers and config objects. Originates from Spring configs and is passed in through setters using a similar
    // pattern.
    private FileHelper fileHelper;
    private SynapseHelper synapseHelper;

    /**
     * Constructs this task with the specified task parameters
     * @param param task parameters
     */
    public SynapseDownloadFromTableTask(SynapseDownloadFromTableParameters param) {
        this.param = param;
    }

    /**
     * Wrapper class around the file system. Used by unit tests to test the functionality without hitting the real file
     * system.
     */
    public final void setFileHelper(FileHelper fileHelper) {
        this.fileHelper = fileHelper;
    }

    /** Synapse helper. */
    public final void setSynapseHelper(SynapseHelper synapseHelper) {
        this.synapseHelper = synapseHelper;
    }

    @Override
    public List<File> call() {
        try {
            downloadCsv();
            if (filterNoDataCsvFiles()) {
                // return an empty list, to signify no data
                return ImmutableList.of();
            }
            getColumnInfoFromCsv();

            if (ctx.getColumnInfo().getFileHandleColumnIndexSet().isEmpty()) {
                LOG.info("No file handles columns in file " + ctx.getCsvFilePath() +
                        ". Skipping extracting and downloading file handles.");
            } else {
                extractFileHandleIdsFromCsv();

                if (ctx.getFileHandleIdSet().isEmpty()) {
                    // This is rare but possible.
                    LOG.info("No file handles to download for file " + ctx.getCsvFilePath() +
                            ". Skipping downloading file handles.");
                } else {
                    bulkDownloadFileHandles();
                }
            }

            editCsv();

            return ImmutableList.of(ctx.getCsvFile(), ctx.getBulkDownloadFile());
        } catch (RuntimeException ex) {
            // Cleanup files. No need to leave garbage behind.
            cleanupFiles();
            throw new AsyncTaskExecutionException(ex);
        }
    }

    // TODO doc
    private void downloadCsv() {
        String synapseTableId = param.getSynapseTableId();
        File csvFile = fileHelper.newFile(param.getTempDir(), param.getSchema().getKey().toString() + ".csv");
        String csvFilePath = csvFile.getAbsolutePath();

        Stopwatch downloadCsvStopwatch = Stopwatch.createStarted();
        try {
            String query = String.format(QUERY_TEMPLATE, synapseTableId, param.getHealthCode(), param.getStartDate(),
                    param.getEndDate());
            String csvFileHandleId = synapseHelper.generateFileHandleFromTableQuery(query, synapseTableId);
            synapseHelper.downloadFileHandle(csvFileHandleId, csvFile);
            ctx.setCsvFile(csvFile);
        } catch (AsyncTimeoutException | SynapseException ex) {
            throw new AsyncTaskExecutionException("Error downloading synapse table " + synapseTableId + " to file " +
                    csvFilePath + ": " + ex.getMessage(), ex);
        } finally {
            downloadCsvStopwatch.stop();
            LOG.info("Downloading from synapse table " + synapseTableId + " to file " + csvFilePath + " took " +
                    downloadCsvStopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }
    }

    // TODO doc
    private boolean filterNoDataCsvFiles() {
        List<String> lineList;
        try {
            lineList = fileHelper.readLines(ctx.getCsvFile());
        } catch (IOException ex) {
            throw new AsyncTaskExecutionException("Error counting lines for file " + ctx.getCsvFilePath() + ": " +
                    ex.getMessage(), ex);
        }

        // If there aren't at least 2 lines (1 line headers, 1+ lines of data), then filter out the file, since we
        // don't need it. Don't log or throw, since this could be a normal case.
        if (lineList.size() < 2) {
            LOG.info("No user data found for file " + ctx.getCsvFilePath() + ". Short-circuiting.");

            // cleanup files, since there's no data to keep around anyway
            cleanupFiles();

            return true;
        } else {
            return false;
        }
    }

    // TODO doc
    // Get file handle column indexes. This will tell us if we need to download file handles and inject the paths
    // into the CSV
    private void getColumnInfoFromCsv() {
        try (CsvNullReader csvFileReader = new CsvNullReader(fileHelper.getReader(ctx.getCsvFile()))) {
            // Get first row, the header row. Because of our previous check, we know this row must exist.
            String[] headerRow = csvFileReader.readNext();

            // Iterate through the headers. Identify relevant fields.
            SynapseTableColumnInfo.Builder colInfoBuilder = new SynapseTableColumnInfo.Builder();
            Map<String, String> fieldTypeMap = param.getSchema().getFieldTypeMap();
            for (int i = 0; i < headerRow.length; i++) {
                String oneFieldName = headerRow[i];
                if ("healthCode".equals(oneFieldName)) {
                    // Health code. Definitely not file handle ID.
                    colInfoBuilder.withHealthCodeColumnIndex(i);
                } else {
                    String bridgeType = fieldTypeMap.get(oneFieldName);
                    if (bridgeType != null && UploadSchema.ATTACHMENT_TYPE_SET.contains(bridgeType)) {
                        colInfoBuilder.addFileHandleColumnIndex(i);
                    }
                }
            }
            ctx.setColumnInfo(colInfoBuilder.build());
        } catch (IOException ex) {
            throw new AsyncTaskExecutionException("Error getting column indices from headers from file " +
                    ctx.getCsvFilePath() + ": " + ex.getMessage(), ex);
        }
    }

    // TODO doc
    private void extractFileHandleIdsFromCsv() {
        Set<Integer> fileHandleColIdxSet = ctx.getColumnInfo().getFileHandleColumnIndexSet();

        Stopwatch extractFileHandlesStopwatch = Stopwatch.createStarted();
        try (CsvNullReader csvFileReader = new CsvNullReader(fileHelper.getReader(ctx.getCsvFile()))) {
            // Skip header row. We've already processed it.
            csvFileReader.readNext();

            // Iterate through the rows. Using the col idx set, identify file handle IDs.
            String[] row;
            while ((row = csvFileReader.readNext()) != null) {
                for (int oneFileHandleColIdx : fileHandleColIdxSet) {
                    String fileHandleId = row[oneFileHandleColIdx];
                    if (!Strings.isNullOrEmpty(fileHandleId)) {
                        ctx.addFileHandleIds(fileHandleId);
                    }
                }
            }
        } catch (IOException ex) {
            throw new AsyncTaskExecutionException("Error extracting file handle IDs from file "
                    + ctx.getCsvFilePath() + ": " + ex.getMessage(), ex);
        } finally {
            extractFileHandlesStopwatch.stop();
            LOG.info("Extracting file handle IDs from file " + ctx.getCsvFilePath() + " took " +
                    extractFileHandlesStopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }
    }

    // TODO doc
    private void bulkDownloadFileHandles() {
        // download file handles
        File bulkDownloadFile = fileHelper.newFile(param.getTempDir(), param.getSchema().getKey().toString() + ".zip");
        String bulkDownloadFilePath = bulkDownloadFile.getAbsolutePath();

        Stopwatch bulkDownloadStopwatch = Stopwatch.createStarted();
        BulkFileDownloadResponse bulkDownloadResponse;
        try {
            bulkDownloadResponse = synapseHelper.generateBulkDownloadFileHandle(param.getSynapseTableId(),
                    ctx.getFileHandleIdSet());
            ctx.setFileSummaryList(bulkDownloadResponse.getFileSummary());

            String bulkDownloadFileHandleId = bulkDownloadResponse.getResultZipFileHandleId();
            synapseHelper.downloadFileHandle(bulkDownloadFileHandleId, bulkDownloadFile);
            ctx.setBulkDownloadFile(bulkDownloadFile);
        } catch (AsyncTimeoutException | SynapseException ex) {
            throw new AsyncTaskExecutionException("Error bulk downloading file handles to file " +
                    bulkDownloadFilePath + ": " + ex.getMessage(), ex);
        } finally {
            bulkDownloadStopwatch.stop();
            LOG.info("Bulk downloading file handles to file " + bulkDownloadFilePath + " took " +
                    bulkDownloadStopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }
    }

    // TODO doc
    // We need to make edits to the CSV. (1) Replace the file handle IDs with zip entry names. (2) Remove health
    // codes, since those aren't supposed to be exposed to users.
    private void editCsv() {
        // Convert file summary in bulk download response into a map from file handle ID to zip entry name.
        Map<String, String> fileHandleIdToZipEntryName = new HashMap<>();
        List<FileDownloadSummary> fileSummaryList = ctx.getFileSummaryList();
        if (fileSummaryList != null && !fileSummaryList.isEmpty()) {
            for (FileDownloadSummary oneFileSummary : fileSummaryList) {
                String fileHandleId = oneFileSummary.getFileHandleId();
                String zipEntryName = oneFileSummary.getZipEntryName();
                if (!Strings.isNullOrEmpty(fileHandleId) && !Strings.isNullOrEmpty(zipEntryName)) {
                    fileHandleIdToZipEntryName.put(fileHandleId, zipEntryName);
                }
            }
        }

        int healthCodeIdx = ctx.getColumnInfo().getHealthCodeColumnIndex();
        Set<Integer> fileHandleColIdxSet = ctx.getColumnInfo().getFileHandleColumnIndexSet();
        File editedCsvFile = fileHelper.newFile(param.getTempDir(), param.getSchema().getKey().toString() +
                "-edited.csv");
        String editedCsvFilePath = editedCsvFile.getAbsolutePath();
        ctx.setEditedCsvFile(editedCsvFile);

        Stopwatch editCsvStopwatch = Stopwatch.createStarted();
        try (CsvNullReader csvFileReader = new CsvNullReader(fileHelper.getReader(ctx.getCsvFile()));
                CSVWriter modifiedCsvFileWriter = new CSVWriter(fileHelper.getWriter(editedCsvFile))) {
            // Copy headers.
            modifiedCsvFileWriter.writeNext(csvFileReader.readNext());

            // Iterate through the rows, replacing the file handle IDs with zip entry names.
            String[] row;
            while ((row = csvFileReader.readNext()) != null) {
                // Clear health code.
                row[healthCodeIdx] = null;

                // Replace file handle IDs with zip entry names (if known)
                for (int oneFileHandleColIdx : fileHandleColIdxSet) {
                    String fileHandleId = row[oneFileHandleColIdx];
                    if (Strings.isNullOrEmpty(fileHandleId)) {
                        // blank column, skip
                        continue;
                    }

                    String zipEntryName = fileHandleIdToZipEntryName.get(fileHandleId);
                    if (!Strings.isNullOrEmpty(zipEntryName)) {
                        row[oneFileHandleColIdx] = zipEntryName;
                    } else {
                        row[oneFileHandleColIdx] = ERROR_DOWNLOADING_ATTACHMENT;
                    }
                }

                // Write modified row to modifiedCsvFileWriter
                modifiedCsvFileWriter.writeNext(row);
            }
        } catch (IOException ex) {
            throw new AsyncTaskExecutionException("Error updating attachment file paths in file " +
                    editedCsvFilePath + ": " + ex.getMessage(), ex);
        } finally {
            editCsvStopwatch.stop();
            LOG.info("Updating attachment file paths in file " + editedCsvFilePath + " took " +
                    editCsvStopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }

        // rename editedCsvFile into csvFile, replacing the original csvFile
        try {
            fileHelper.move(editedCsvFile, ctx.getCsvFile());
        } catch (IOException ex) {
            throw new AsyncTaskExecutionException("Error moving (replacing) file from " + editedCsvFilePath +
                    " to " + ctx.getCsvFilePath() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * This is called when an error is thrown or if there's no data to download. We'll need to delete all intermediate
     * files to ensure we leave the file system in the state we started it in.
     */
    private void cleanupFiles() {
        for (File oneFile : ImmutableList.of(ctx.getCsvFile(), ctx.getBulkDownloadFile(), ctx.getEditedCsvFile())) {
            if (oneFile == null || !fileHelper.exists(oneFile)) {
                // No file. No need to cleanup.
                continue;
            }

            try {
                fileHelper.deleteFile(oneFile);
            } catch (IOException ex) {
                LOG.error("Error cleaning up file " + oneFile.getAbsolutePath() + ": " + ex.getMessage(), ex);
            }
        }
    }
}
