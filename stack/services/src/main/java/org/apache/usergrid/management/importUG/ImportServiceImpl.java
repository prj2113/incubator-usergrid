/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.usergrid.management.importUG;

import org.apache.usergrid.batch.JobExecution;
import org.apache.usergrid.batch.service.SchedulerService;
import org.apache.usergrid.management.ApplicationInfo;
import org.apache.usergrid.management.ManagementService;
import org.apache.usergrid.management.OrganizationInfo;
import org.apache.usergrid.persistence.*;
import org.apache.usergrid.persistence.entities.FileImport;
import org.apache.usergrid.persistence.entities.Import;
import org.apache.usergrid.persistence.entities.JobData;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.usergrid.persistence.cassandra.CassandraService.MANAGEMENT_APPLICATION_ID;

/**
 * Created by ApigeeCorporation on 7/8/14.
 */
public class ImportServiceImpl implements ImportService {

    public static final String IMPORT_ID = "importId";
    public static final String IMPORT_JOB_NAME = "importJob";
    public static final String FILE_IMPORT_ID = "fileImportId";
    public static final String FILE_IMPORT_JOB_NAME = "fileImportJob";

    //Amount of time that has passed before sending another heart beat in millis
    public static final int TIMESTAMP_DELTA = 5000;

    private static final Logger logger = LoggerFactory.getLogger(ImportServiceImpl.class);

    //injected the Entity Manager Factory
    protected EntityManagerFactory emf;
    private ArrayList<File> files;

    //dependency injection
    private SchedulerService sch;

    //inject Management Service to access Organization Data
    private ManagementService managementService;
    private JsonFactory jsonFactory = new JsonFactory();

    private int entityCount = 0;

    /**
     * This schedules the main import Job
     *
     * @param config configuration of the job to be scheduled
     * @return it returns the UUID of the scheduled job
     * @throws Exception
     */
    @Override
    public UUID schedule(Map<String, Object> config) throws Exception {

        if (config == null) {
            logger.error("import information cannot be null");
            return null;
        }

        EntityManager rootEm = null;
        try {
            rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
            Set<String> collections = rootEm.getApplicationCollections();
            if (!collections.contains("imports")) {
                rootEm.createApplicationCollection("imports");
            }
        } catch (Exception e) {
            logger.error("application doesn't exist within the current context");
            return null;
        }

        Import importUG = new Import();

        // create the import entity to store all metadata about the import job
        try {
            importUG = rootEm.create(importUG);
        } catch (Exception e) {
            logger.error("Import entity creation failed");
            return null;
        }

        // update state for import job to created
        importUG.setState(Import.State.CREATED);
        rootEm.update(importUG);

        // set data to be transferred to importInfo
        JobData jobData = new JobData();
        jobData.setProperty("importInfo", config);
        jobData.setProperty(IMPORT_ID, importUG.getUuid());

        long soonestPossible = System.currentTimeMillis() + 250; //sch grace period

        // schedule import job
        sch.createJob(IMPORT_JOB_NAME, soonestPossible, jobData);

        // update state for import job to created
        importUG.setState(Import.State.SCHEDULED);
        rootEm.update(importUG);

        return importUG.getUuid();
    }

    /**
     * This schedules the sub  FileImport Job
     *
     * @param file file to be scheduled
     * @return it returns the UUID of the scheduled job
     * @throws Exception
     */
    public UUID scheduleFile(String file, EntityRef importRef) throws Exception {

        EntityManager rootEm = null;

        try {
            rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
        } catch (Exception e) {
            logger.error("application doesn't exist within the current context");
            return null;
        }

        // create a FileImport entity to store metadata about the fileImport job
        FileImport fileImport = new FileImport();

        fileImport.setFileName(file);
        fileImport.setCompleted(false);
        fileImport.setLastUpdatedUUID(" ");
        fileImport.setErrorMessage(" ");
        fileImport.setState(FileImport.State.CREATED);
        fileImport = rootEm.create(fileImport);

        Import importUG = rootEm.get(importRef, Import.class);

        try {
            // create a connection between the main import job and the sub FileImport Job
            rootEm.createConnection(importUG, "includes", fileImport);
        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        }

        // mark the File Import Job as created
        fileImport.setState(FileImport.State.CREATED);
        rootEm.update(fileImport);

        //set data to be transferred to the FileImport Job
        JobData jobData = new JobData();
        jobData.setProperty("File", file);
        jobData.setProperty(FILE_IMPORT_ID, fileImport.getUuid());

        long soonestPossible = System.currentTimeMillis() + 250; //sch grace period

        //schedule file import job
        sch.createJob(FILE_IMPORT_JOB_NAME, soonestPossible, jobData);

        //update state of the job to Scheduled
        fileImport.setState(FileImport.State.SCHEDULED);
        rootEm.update(fileImport);

        return fileImport.getUuid();
    }

    /**
     * Query Entity Manager for the state of the Import Entity. This corresponds to the GET /import
     *
     * @return String
     */
    @Override
    public String getState(UUID uuid) throws Exception {
        if (uuid == null) {
            logger.error("UUID passed in cannot be null.");
            return "UUID passed in cannot be null";
        }

        EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

        //retrieve the import entity.
        Import importUG = rootEm.get(uuid, Import.class);

        if (importUG == null) {
            logger.error("no entity with that uuid was found");
            return "No Such Element found";
        }
        return importUG.getState().toString();
    }

    /**
     * Query Entity Manager for the error message generated for an import job.
     *
     * @return String
     */
    @Override
    public String getErrorMessage(final UUID uuid) throws Exception {

        //get application entity manager

        if (uuid == null) {
            logger.error("UUID passed in cannot be null.");
            return "UUID passed in cannot be null";
        }

        EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

        //retrieve the import entity.
        Import importUG = rootEm.get(uuid, Import.class);

        if (importUG == null) {
            logger.error("no entity with that uuid was found");
            return "No Such Element found";
        }
        return importUG.getErrorMessage().toString();
    }

    /**
     * Returns the Import Entity that stores all meta-data for the particular import Job
     * @param jobExecution the import job details
     * @return Import Entity
     * @throws Exception
     */
    @Override
    public Import getImportEntity(final JobExecution jobExecution) throws Exception {

        UUID importId = (UUID) jobExecution.getJobData().getProperty(IMPORT_ID);
        EntityManager importManager = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

        return importManager.get(importId, Import.class);
    }


    /**
     * Returns the File Import Entity that stores all meta-data for the particular sub File import Job
     * @param jobExecution the file import job details
     * @return File Import Entity
     * @throws Exception
     */
    @Override
    public FileImport getFileImportEntity(final JobExecution jobExecution) throws Exception {

        UUID fileImportId = (UUID) jobExecution.getJobData().getProperty(FILE_IMPORT_ID);
        EntityManager em = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

        return em.get(fileImportId, FileImport.class);
    }

    /**
     * This returns the temporary files downloaded form s3
     */
    @Override
    public ArrayList<File> getEphemeralFile() {
        return files;
    }

    public SchedulerService getSch() {
        return sch;
    }


    public void setSch(final SchedulerService sch) {
        this.sch = sch;
    }


    public EntityManagerFactory getEmf() {
        return emf;
    }


    public void setEmf(final EntityManagerFactory emf) {
        this.emf = emf;
    }


    public ManagementService getManagementService() {

        return managementService;
    }


    public void setManagementService(final ManagementService managementService) {
        this.managementService = managementService;
    }

    /**
     * This method gets the files from s3 and also creates sub-jobs for each file i.e. File Import Jobs
     * @param jobExecution the job created by the scheduler with all the required config data
     * @throws Exception
     */
    @Override
    public void doImport(JobExecution jobExecution) throws Exception {

        Map<String, Object> config = (Map<String, Object>) jobExecution.getJobData().getProperty("importInfo");
        Object s3PlaceHolder = jobExecution.getJobData().getProperty("s3Import");
        S3Import s3Import = null;

        if (config == null) {
            logger.error("Import Information passed through is null");
            return;
        }

        //get the entity manager for the application, and the entity that this Import corresponds to.
        UUID importId = (UUID) jobExecution.getJobData().getProperty(IMPORT_ID);

        EntityManager rooteEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
        Import importUG = rooteEm.get(importId, Import.class);

        //update the entity state to show that the job has officially started.
        importUG.setState(Import.State.STARTED);
        importUG.setStarted(System.currentTimeMillis());
        importUG.setErrorMessage(" ");
        rooteEm.update(importUG);
        try {
            if (s3PlaceHolder != null) {
                s3Import = (S3Import) s3PlaceHolder;
            } else {
                s3Import = new S3ImportImpl();
            }
        } catch (Exception e) {
            logger.error("S3Import doesn't exist");
            importUG.setErrorMessage(e.getMessage());
            importUG.setState(Import.State.FAILED);
            rooteEm.update(importUG);
            return;
        }

        try {

            if (config.get("organizationId") == null) {
                logger.error("No organization could be found");
                importUG.setErrorMessage("No organization could be found");
                importUG.setState(Import.State.FAILED);
                rooteEm.update(importUG);
                return;
            } else if (config.get("applicationId") == null) {
                //import All the applications from an organization
                importApplicationsFromOrg((UUID) config.get("organizationId"), config, jobExecution, s3Import);
            } else if (config.get("collectionName") == null) {
                //imports an Application from a single organization
                importApplicationFromOrg((UUID) config.get("organizationId"), (UUID) config.get("applicationId"), config, jobExecution, s3Import);
            } else {
                //imports a single collection from an app org combo
                importCollectionFromOrgApp((UUID) config.get("applicationId"), config, jobExecution, s3Import);
            }

        } catch (OrganizationNotFoundException e) {
            importUG.setErrorMessage(e.getMessage());
            importUG.setState(Import.State.FINISHED);
            rooteEm.update(importUG);
            return;
        } catch (ApplicationNotFoundException e) {
            importUG.setErrorMessage(e.getMessage());
            importUG.setState(Import.State.FINISHED);
            rooteEm.update(importUG);
            return;
        }

        if (files.size() == 0) {

            importUG.setState(Import.State.FINISHED);
            importUG.setErrorMessage("no files found in the bucket with the relevant context");
            rooteEm.update(importUG);

        } else {

            Map<String, Object> fileMetadata = new HashMap<String, Object>();

            ArrayList<Map<String, Object>> value = new ArrayList<Map<String, Object>>();

            // schedule each file as a separate job
            for (File eachfile : files) {

                UUID jobID = scheduleFile(eachfile.getPath(), rooteEm.getRef(importId));
                Map<String, Object> fileJobID = new HashMap<String, Object>();
                fileJobID.put("FileName", eachfile.getName());
                fileJobID.put("JobID", jobID.toString());
                value.add(fileJobID);
            }

            fileMetadata.put("files", value);
            importUG.addProperties(fileMetadata);
            rooteEm.update(importUG);
        }
        return;
    }

    /**
     * Imports a specific collection from an org-app combo.
     */
    private void importCollectionFromOrgApp(UUID applicationUUID, final Map<String, Object> config,
                                            final JobExecution jobExecution, S3Import s3Import) throws Exception {

        //retrieves import entity
        Import importUG = getImportEntity(jobExecution);
        ApplicationInfo application = managementService.getApplicationInfo(applicationUUID);

        if (application == null) {
            throw new ApplicationNotFoundException("Application Not Found");
        }

        String collectionName = config.get("collectionName").toString();

        // prepares the prefix path for the files to be imported depending on the endpoint being hit
        String appFileName = prepareInputFileName("application", application.getName(), collectionName);

        files = fileTransfer(importUG, appFileName, config, s3Import, 0);

    }

    /**
     * Imports a specific applications from an organization
     */
    private void importApplicationFromOrg(UUID organizationUUID, UUID applicationId, final Map<String, Object> config,
                                          final JobExecution jobExecution, S3Import s3Import) throws Exception {

        //retrieves import entity
        Import importUG = getImportEntity(jobExecution);

        ApplicationInfo application = managementService.getApplicationInfo(applicationId);

        if (application == null) {
            throw new ApplicationNotFoundException("Application Not Found");
        }

        // prepares the prefix path for the files to be imported depending on the endpoint being hit
        String appFileName = prepareInputFileName("application", application.getName(), null);

        files = fileTransfer(importUG, appFileName, config, s3Import, 1);

    }

    /**
     * Imports All Applications from an Organization
     */
    private void importApplicationsFromOrg(UUID organizationUUID, final Map<String, Object> config,
                                           final JobExecution jobExecution, S3Import s3Import) throws Exception {

        // retrieves import entity
        Import importUG = getImportEntity(jobExecution);
        String appFileName = null;

        OrganizationInfo organizationInfo = managementService.getOrganizationByUuid(organizationUUID);
        if (organizationInfo == null) {
            throw new OrganizationNotFoundException("Organization Not Found");
        }

        // prepares the prefix path for the files to be imported depending on the endpoint being hit
        appFileName = prepareInputFileName("organization", organizationInfo.getName(), null);

        files = fileTransfer(importUG, appFileName, config, s3Import, 2);

    }

    /**
     * prepares the prefix path for the files to be imported depending on the endpoint being hit
     * @param type just a label such us: organization, application.
     * @return the file name concatenated with the type and the name of the collection
     */
    protected String prepareInputFileName(String type, String name, String CollectionName) {
        StringBuilder str = new StringBuilder();

        // in case of type organization --> the file name will be "<org_name>/"
        if (type.equals("organization")) {

            str.append(name);
            str.append("/");

        } else if (type.equals("application")) {

            // in case of type application --> the file name will be "<org_name>/<app_name>."
            str.append(name);
            str.append(".");

            if (CollectionName != null) {

                // in case of type application and collection import --> the file name will be "<org_name>/<app_name>.<collection_name>."
                str.append(CollectionName);
                str.append(".");

            }
        }

        String inputFileName = str.toString();

        return inputFileName;
    }

    /**
     * @param importUG    Import instance
     * @param appFileName the base file name for the files to be downloaded
     * @param config      the config information for the import job
     * @param s3Import    s3import instance
     * @param type        it indicates the type of import. 0 - Collection , 1 - Application and 2 - Organization
     * @return
     */
    public ArrayList<File> fileTransfer(Import importUG, String appFileName, Map<String, Object> config,
                                        S3Import s3Import, int type) throws Exception {

        EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
        ArrayList<File> files = new ArrayList<File>();

        try {
            files = s3Import.copyFromS3(config, appFileName, type);
        } catch (Exception e) {
            importUG.setErrorMessage(e.getMessage());
            importUG.setState(Import.State.FAILED);
            rootEm.update(importUG);
        }
        return files;
    }

    /**
     * The loops through each temp file and parses it to store the entities from the json back into usergrid
     *
     * @throws Exception
     */
    @Override
    public void FileParser(JobExecution jobExecution) throws Exception {

        // add properties to the import entity
        FileImport fileImport = getFileImportEntity(jobExecution);

        File file = new File(jobExecution.getJobData().getProperty("File").toString());

        EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
        rootEm.update(fileImport);

        boolean completed = fileImport.getCompleted();

        // on resume, completed files will not be traversed again
        if (!completed) {

            // validates the JSON structure
            if (isValidJSON(file, rootEm, fileImport)) {

                // mark the File import job as started
                fileImport.setState(FileImport.State.STARTED);
                rootEm.update(fileImport);

                // gets the application anme from the filename
                String applicationName = file.getPath().split("\\.")[0];

                ApplicationInfo application = managementService.getApplicationInfo(applicationName);

                JsonParser jp = getJsonParserForFile(file);

                // incase of resume, retrieve the last updated UUID for this file
                String lastUpdatedUUID = fileImport.getLastUpdatedUUID();

                // this handles partially completed files by updating entities from the point of failure
                if (!lastUpdatedUUID.equals(" ")) {

                    // go till the last updated entity
                    while (!jp.getText().equals(lastUpdatedUUID)) {
                        jp.nextToken();
                    }

                    // skip the last one and start from the next one
                    while (!(jp.getCurrentToken() == JsonToken.END_OBJECT && jp.nextToken() == JsonToken.START_OBJECT)) {
                        jp.nextToken();
                    }
                }

                // get to start of an object i.e next entity.
                while (jp.getCurrentToken() != JsonToken.START_OBJECT) {
                    jp.nextToken();
                }

                // get entity manager for the application
                EntityManager em = emf.getEntityManager(application.getId());

                while (jp.nextToken() != JsonToken.END_ARRAY) {
                    // import the entities in this file
                    importEntityStuff(jp, em, rootEm, fileImport, jobExecution);
                }
                jp.close();

                // Updates the state of file import job
                if (!fileImport.getState().equals("FAILED")) {

                    // mark file as completed
                    fileImport.setCompleted(true);
                    fileImport.setState(FileImport.State.FINISHED);
                    rootEm.update(fileImport);

                    //check other files status and mark the status of import Job as Finished if all others are finished
                    Results ImportJobResults = rootEm.getConnectingEntities(fileImport.getUuid(), "includes", null, Results.Level.ALL_PROPERTIES);
                    List<Entity> importEntity = ImportJobResults.getEntities();
                    UUID importId = importEntity.get(0).getUuid();
                    Import importUG = rootEm.get(importId, Import.class);

                    Results entities = rootEm.getConnectedEntities(importId, "includes", null, Results.Level.ALL_PROPERTIES);
                    List<Entity> importFile = entities.getEntities();

                    int count = 0;
                    for (Entity eachEntity : importFile) {
                        FileImport fi = rootEm.get(eachEntity.getUuid(), FileImport.class);
                        if (fi.getState().toString().equals("FINISHED")) {
                            count++;
                        } else if (fi.getState().toString().equals("FAILED")) {
                            importUG.setState(Import.State.FAILED);
                            rootEm.update(importUG);
                            break;
                        }
                    }
                    if (count == importFile.size()) {
                        importUG.setState(Import.State.FINISHED);
                        rootEm.update(importUG);
                    }
                }
            }
        }
    }

    /**
     * Checks if a file is a valid JSON
     * @param collectionFile the file being validated
     * @param rootEm    the Entity Manager for the Management application
     * @param fileImport the file import entity
     * @return
     * @throws Exception
     */
    private boolean isValidJSON(File collectionFile, EntityManager rootEm, FileImport fileImport) throws Exception {

        boolean valid = false;
        try {
            final JsonParser jp = jsonFactory.createJsonParser(collectionFile);
            while (jp.nextToken() != null) {
            }
            valid = true;
        } catch (JsonParseException e) {
            e.printStackTrace();
            fileImport.setErrorMessage(e.getMessage());
            rootEm.update(fileImport);
        } catch (IOException e) {
            fileImport.setErrorMessage(e.getMessage());
            rootEm.update(fileImport);
        }
        return valid;
    }


    /**
     * Gets the JSON parser for given file
     * @param collectionFile the file for which JSON parser is required
     * @return
     * @throws Exception
     */
    private JsonParser getJsonParserForFile(File collectionFile) throws Exception {
        JsonParser jp = jsonFactory.createJsonParser(collectionFile);
        jp.setCodec(new ObjectMapper());
        return jp;
    }



    /**
     * Imports the entity's connecting references (collections, connections and dictionaries)
     * @param jp  JsonParser pointing to the beginning of the object.
     * @param em Entity Manager for the application being imported
     * @param rootEm Entity manager for the root applicaition
     * @param fileImport the file import entity
     * @param jobExecution  execution details for the import jbo
     * @throws Exception
     */
    private void importEntityStuff(final JsonParser jp, final EntityManager em, final EntityManager rootEm, final FileImport fileImport, final JobExecution jobExecution) throws Exception {

        final JsonParserObservable subscribe = new JsonParserObservable(jp, em, rootEm, fileImport);

        final Observable<WriteEvent> observable = Observable.create(subscribe);

        /**
         * This is the action we want to perform for every UUID we receive
         */
        final Action1<WriteEvent> doWork = new Action1<WriteEvent>() {
            @Override
            public void call(WriteEvent writeEvent) {
                writeEvent.doWrite(em, jobExecution, fileImport);
            }

        };


        final AtomicLong entityCounter = new AtomicLong();
        final AtomicLong eventCounter = new AtomicLong();
        /**
         * This is boilerplate glue code.  We have to follow this for the parallel operation.  In the "call"
         * method we want to simply return the input observable + the chain of operations we want to invoke
         */
        observable.parallel(new Func1<Observable<WriteEvent>, Observable<WriteEvent>>() {
            @Override
            public Observable<WriteEvent> call(Observable<WriteEvent> entityWrapperObservable) {

                /* TODO:
                 * need to fixed so that number of entities created can be counted correctly
                 * and also update the last updated UUID for the fileImport which is a must for resumability
                 */
//                return entityWrapperObservable.doOnNext(doWork).doOnNext(new Action1<WriteEvent>() {
//
//                         @Override
//                         public void call(WriteEvent writeEvent) {
//                             if (!(writeEvent instanceof EntityEvent)) {
//                                 final long val = eventCounter.incrementAndGet();
//                                 if(val % 50 == 0) {
//                                     jobExecution.heartbeat();
//                                 }
//                                 return;
//                             }
//
//                             final long value = entityCounter.incrementAndGet();
//                             if (value % 2000 == 0) {
//                                 try {
//                                     logger.error("UUID = " +((EntityEvent) writeEvent).getEntityUuid().toString() + " value = " + value +"");
//                                     fileImport.setLastUpdatedUUID(((EntityEvent) writeEvent).getEntityUuid().toString());
//                                     //checkpoint the UUID here.
//                                     rootEm.update(fileImport);
//                                 } catch(Exception ex) {}
//                             }
//                             if(value % 100 == 0) {
//                                 logger.error("heartbeat sent by " + fileImport.getFileName());
//                                 jobExecution.heartbeat();
//                             }
//                         }
//                     }
//                );

                return entityWrapperObservable.doOnNext(doWork);
            }
        }, Schedulers.io()).toBlocking().last();
    }

    private interface WriteEvent {
        public void doWrite(EntityManager em, JobExecution jobExecution, FileImport fileImport);
    }

    private final class EntityEvent implements WriteEvent {
        UUID entityUuid;
        String entityType;
        Map<String, Object> properties;

        EntityEvent(UUID entityUuid, String entityType, Map<String, Object> properties) {
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.properties = properties;
        }

        public UUID getEntityUuid() {
            return entityUuid;
        }

        // Creates entities
        @Override
        public void doWrite(EntityManager em, JobExecution jobExecution, FileImport fileImport) {
            EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

            try {
                em.create(entityUuid, entityType, properties);
            } catch (Exception e) {
                fileImport.setErrorMessage(e.getMessage());
                try {
                    rootEm.update(fileImport);
                } catch (Exception ex) {
                }
            }
        }
    }

    private final class ConnectionEvent implements WriteEvent {
        EntityRef ownerEntityRef;
        String connectionType;
        EntityRef entryRef;

        ConnectionEvent(EntityRef ownerEntityRef, String connectionType, EntityRef entryRef) {
            this.ownerEntityRef = ownerEntityRef;
            this.connectionType = connectionType;
            this.entryRef = entryRef;

        }

        // creates connections between entities
        @Override
        public void doWrite(EntityManager em, JobExecution jobExecution, FileImport fileImport) {
            EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);

            try {
                em.createConnection(ownerEntityRef, connectionType, entryRef);
            } catch (Exception e) {
                fileImport.setErrorMessage(e.getMessage());
                try {
                    rootEm.update(fileImport);
                } catch (Exception ex) {
                }
            }
        }
    }

    private final class DictionaryEvent implements WriteEvent {

        EntityRef ownerEntityRef;
        String dictionaryName;
        Map<String, Object> dictionary;

        DictionaryEvent(EntityRef ownerEntityRef, String dictionaryName, Map<String, Object> dictionary) {
            this.ownerEntityRef = ownerEntityRef;
            this.dictionaryName = dictionaryName;
            this.dictionary = dictionary;
        }

        // adds map to the dictionary
        @Override
        public void doWrite(EntityManager em, JobExecution jobExecution, FileImport fileImport) {
            EntityManager rootEm = emf.getEntityManager(MANAGEMENT_APPLICATION_ID);
            try {
                em.addMapToDictionary(ownerEntityRef, dictionaryName, dictionary);
            } catch (Exception e) {
                fileImport.setErrorMessage(e.getMessage());
                try {
                    rootEm.update(fileImport);
                } catch (Exception ex) {
                }
            }
        }
    }


    private final class JsonParserObservable implements Observable.OnSubscribe<WriteEvent> {
        private final JsonParser jp;
        EntityManager em;
        EntityManager rootEm;
        FileImport fileImport;


        JsonParserObservable(JsonParser parser, EntityManager em, EntityManager rootEm, FileImport fileImport) {
            this.jp = parser;
            this.em = em;
            this.rootEm = rootEm;
            this.fileImport = fileImport;
        }

        @Override
        public void call(final Subscriber<? super WriteEvent> subscriber) {

            WriteEvent entityWrapper = null;
            EntityRef ownerEntityRef = null;
            String entityUuid = "";
            String entityType = "";
            try {
                while (!subscriber.isUnsubscribed() && jp.nextToken() != JsonToken.END_OBJECT) {
                    String collectionName = jp.getCurrentName();

                    // create the  wrapper for connections
                    if (collectionName.equals("connections")) {

                        jp.nextToken(); // START_OBJECT
                        while (jp.nextToken() != JsonToken.END_OBJECT) {
                            String connectionType = jp.getCurrentName();

                            jp.nextToken(); // START_ARRAY
                            while (jp.nextToken() != JsonToken.END_ARRAY) {
                                String entryId = jp.getText();

                                EntityRef entryRef = new SimpleEntityRef(UUID.fromString(entryId));
                                entityWrapper = new ConnectionEvent(ownerEntityRef, connectionType, entryRef);

                                // Creates a new subscriber to the observer with the given connection wrapper
                                subscriber.onNext(entityWrapper);
                            }
                        }

                    }
                    // create the  wrapper for dictionaries
                    else if (collectionName.equals("dictionaries")) {

                        jp.nextToken(); // START_OBJECT
                        while (jp.nextToken() != JsonToken.END_OBJECT) {

                            String dictionaryName = jp.getCurrentName();

                            jp.nextToken();

                            Map<String, Object> dictionary = jp.readValueAs(HashMap.class);
                            entityWrapper = new DictionaryEvent(ownerEntityRef, dictionaryName, dictionary);

                            // Creates a new subscriber to the observer with the given dictionary wrapper
                            subscriber.onNext(entityWrapper);
                        }
                        subscriber.onCompleted();

                    } else {

                        // Regular collections
                        jp.nextToken(); // START_OBJECT

                        Map<String, Object> properties = new HashMap<String, Object>();
                        JsonToken token = jp.nextToken();

                        while (token != JsonToken.END_OBJECT) {
                            if (token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NUMBER_INT) {
                                String key = jp.getCurrentName();
                                if (key.equals("uuid")) {
                                    entityUuid = jp.getText();

                                } else if (key.equals("type")) {
                                    entityType = jp.getText();
                                } else if (key.length() != 0 && jp.getText().length() != 0) {
                                    String value = jp.getText();
                                    properties.put(key, value);
                                }
                            }
                            token = jp.nextToken();
                        }

                        ownerEntityRef = new SimpleEntityRef(entityType, UUID.fromString(entityUuid));
                        entityWrapper = new EntityEvent(UUID.fromString(entityUuid), entityType, properties);

                        // Creates a new subscriber to the observer with the given dictionary wrapper
                        subscriber.onNext(entityWrapper);

                    }
                }
            } catch (Exception e) {
                // skip illegal entity UUID and go to next one
                fileImport.setErrorMessage(e.getMessage());
                try {
                    rootEm.update(fileImport);
                } catch (Exception ex) {
                }
                subscriber.onError(e);
            }
        }
    }
}

/**
 * Custom Exception class for Organization Not Found
 */
class OrganizationNotFoundException extends Exception {
    OrganizationNotFoundException(String s) {
        super(s);
    }
}

/**
 * Custom Exception class for Application Not Found
 */
class ApplicationNotFoundException extends Exception {
    ApplicationNotFoundException(String s) {
        super(s);
    }
}
