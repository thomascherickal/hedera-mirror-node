package com.hedera.mirror.importer.parser.record;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.base.Stopwatch;
import com.google.protobuf.TextFormat;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Named;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Hex;
import org.springframework.scheduling.annotation.Scheduled;

import com.hedera.mirror.importer.domain.ApplicationStatusCode;
import com.hedera.mirror.importer.parser.FileParser;
import com.hedera.mirror.importer.repository.ApplicationStatusRepository;
import com.hedera.mirror.importer.util.FileDelimiter;
import com.hedera.mirror.importer.util.ShutdownHelper;
import com.hedera.mirror.importer.util.Utility;

/**
 * This is a utility file to read back service record file generated by Hedera node
 */
@Log4j2
@Named
public class RecordFileParser implements FileParser {

    private final ApplicationStatusRepository applicationStatusRepository;
    private final RecordParserProperties parserProperties;

    public RecordFileParser(ApplicationStatusRepository applicationStatusRepository,
                            RecordParserProperties parserProperties) {
        this.applicationStatusRepository = applicationStatusRepository;
        this.parserProperties = parserProperties;
    }

    /**
     * Given a service record name, read its prevFileHash
     *
     * @param fileName the name of record file to read
     * @return return previous file hash's Hex String
     */
    public static String readPrevFileHash(String fileName) {
        File file = new File(fileName);
        if (file.exists() == false) {
            log.warn("File does not exist {}", fileName);
            return null;
        }
        byte[] prevFileHash = new byte[48];
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            // record_format_version
            dis.readInt();
            // version
            dis.readInt();

            byte typeDelimiter = dis.readByte();

            if (typeDelimiter == FileDelimiter.RECORD_TYPE_PREV_HASH) {
                dis.read(prevFileHash);
                String hexString = Hex.encodeHexString(prevFileHash);
                log.trace("Read previous file hash {} for file {}", hexString, fileName);
                return hexString;
            } else {
                log.error("Expecting previous file hash, but found file delimiter {} for file {}", typeDelimiter,
                        fileName);
            }
        } catch (Exception e) {
            log.error("Error reading previous file hash {}", fileName, e);
        }

        return null;
    }

    /**
     * Given a service record name, read and parse and return as a list of service record pair
     *
     * @param fileName         the name of record file to read
     * @param previousFileHash the hash of the previous record file in the series
     * @param thisFileHash     the hash of this file
     * @return return boolean indicating method success
     * @throws Exception
     */
    private boolean loadRecordFile(String fileName, String previousFileHash, String thisFileHash) throws Exception {

        File file = new File(fileName);
        String newFileHash = "";

        if (file.exists() == false) {
            log.warn("File does not exist {}", fileName);
            return false;
        }
        long counter = 0;
        byte[] readFileHash = new byte[48];
        RecordFileLogger.INIT_RESULT initFileResult = RecordFileLogger.initFile(fileName);
        Stopwatch stopwatch = Stopwatch.createStarted();

        if (initFileResult == RecordFileLogger.INIT_RESULT.OK) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                int record_format_version = dis.readInt();
                int version = dis.readInt();

                log.info("Loading version {} record file: {}", record_format_version, file.getName());

                while (dis.available() != 0) {

                    try {
                        byte typeDelimiter = dis.readByte();

                        switch (typeDelimiter) {
                            case FileDelimiter.RECORD_TYPE_PREV_HASH:
                                dis.read(readFileHash);

                                if (Utility.hashIsEmpty(previousFileHash)) {
                                    log.error("Previous file hash not available");
                                    previousFileHash = Hex.encodeHexString(readFileHash);
                                }

                                newFileHash = Hex.encodeHexString(readFileHash);

                                log.trace("New file hash = {}, old hash = {}", newFileHash, previousFileHash);

                                if (!newFileHash.contentEquals(previousFileHash)) {

                                    if (applicationStatusRepository
                                            .findByStatusCode(ApplicationStatusCode.RECORD_HASH_MISMATCH_BYPASS_UNTIL_AFTER)
                                            .compareTo(Utility.getFileName(fileName)) < 0) {
                                        // last file for which mismatch is allowed is in the past
                                        log.error("Hash mismatch for file {}. Previous = {}, Current = {}", fileName,
                                                previousFileHash, newFileHash);
                                        RecordFileLogger.rollback();
                                        return false;
                                    }
                                }
                                break;
                            case FileDelimiter.RECORD_TYPE_RECORD:
                                counter++;

                                int byteLength = dis.readInt();
                                byte[] rawBytes = new byte[byteLength];
                                dis.readFully(rawBytes);
                                Transaction transaction = Transaction.parseFrom(rawBytes);

                                byteLength = dis.readInt();
                                rawBytes = new byte[byteLength];
                                dis.readFully(rawBytes);

                                TransactionRecord txRecord = TransactionRecord.parseFrom(rawBytes);
                                RecordFileLogger.storeRecord(transaction, txRecord, rawBytes);

                                if (log.isTraceEnabled()) {
                                    log.trace("Transaction = {}, Record = {}", Utility
                                            .printTransaction(transaction), TextFormat.shortDebugString(txRecord));
                                } else {
                                    log.debug("Stored transaction with consensus timestamp {}", txRecord
                                            .getConsensusTimestamp());
                                }
                                break;
                            case FileDelimiter.RECORD_TYPE_SIGNATURE:
                                int sigLength = dis.readInt();
                                byte[] sigBytes = new byte[sigLength];
                                dis.readFully(sigBytes);
                                log.trace("File {} has signature {}", fileName, Hex.encodeHexString(sigBytes));
                                break;

                            default:
                                log.error("Unknown record file delimiter {} for file {}", typeDelimiter, file);
                                RecordFileLogger.rollback();
                                return false;
                        }
                    } catch (Exception e) {
                        log.error("Exception {}", e);
                        RecordFileLogger.rollback();
                        return false;
                    }
                }

                log.trace("Calculated file hash for the current file {}", thisFileHash);

                RecordFileLogger.completeFile(thisFileHash, previousFileHash);
            } catch (Exception e) {
                log.error("Error parsing record file {} after {}", file, stopwatch, e);
                RecordFileLogger.rollback();
                return false;
            }

            log.info("Finished parsing {} transactions from record file {} in {}", counter, file.getName(), stopwatch);
            if (!Utility.hashIsEmpty(thisFileHash)) {
                applicationStatusRepository
                        .updateStatusValue(ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH, thisFileHash);
            }
            return true;
        } else if (initFileResult == RecordFileLogger.INIT_RESULT.SKIP) {
            return true;
        } else {
            RecordFileLogger.rollback();
            return false;
        }
    }

    /**
     * read and parse a list of record files
     *
     * @throws Exception
     */
    private void loadRecordFiles(List<String> fileNames) throws Exception {
        String prevFileHash = applicationStatusRepository
                .findByStatusCode(ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH);
        Collections.sort(fileNames);

        for (String name : fileNames) {
            String thisFileHash = "";
            if (ShutdownHelper.isStopping()) {
                return;
            }
            thisFileHash = Hex.encodeHexString(Utility.getFileHash(name));
            if (loadRecordFile(name, prevFileHash, thisFileHash)) {
                prevFileHash = thisFileHash;
                Utility.moveFileToParsedDir(name, "/parsedRecordFiles/");
            } else {
                return;
            }
        }
    }

    @Override
    @Scheduled(fixedRateString = "${hedera.mirror.parser.record.frequency:500}")
    public void parse() {
        try {
            if (!parserProperties.isEnabled()) {
                return;
            }

            if (ShutdownHelper.isStopping()) {
                return;
            }

            Path path = parserProperties.getValidPath();
            log.debug("Parsing record files from {}", path);
            if (RecordFileLogger.start()) {

                File file = path.toFile();
                if (file.isDirectory()) { //if it's a directory

                    String[] files = file.list(); // get all files under the directory
                    Arrays.sort(files);           // sorted by name (timestamp)

                    // add directory prefix to get full path
                    List<String> fullPaths = Arrays.asList(files).stream()
                            .filter(f -> Utility.isRecordFile(f))
                            .map(s -> file + "/" + s)
                            .collect(Collectors.toList());

                    if (fullPaths != null && fullPaths.size() != 0) {
                        log.trace("Processing record files: {}", fullPaths);
                        loadRecordFiles(fullPaths);
                    } else {
                        log.debug("No files to parse");
                    }
                } else {
                    log.error("Input parameter is not a folder: {}", path);
                }
                RecordFileLogger.finish();
            }
        } catch (Exception e) {
            log.error("Error parsing files", e);
        }
    }
}
