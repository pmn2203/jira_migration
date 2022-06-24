package com.customertimes.jira.client;

import com.atlassian.httpclient.apache.httpcomponents.MultiPartEntityBuilder;
import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;

public class MigrationService {

    static class IssueLinkItem {
        final String name;
        final String inward;
        final String outward;
        final String linkIssueKey;
        final String direction;
        IssueLinkItem(String name, String inward, String outward, String linkIssueKey, String direction) {
            this.name = name;
            this.inward = inward;
            this.outward = outward;
            this.direction = direction;
            this.linkIssueKey = linkIssueKey;
        }
    }

    private Logger logger = Logger.getLogger(MigrationService.class);
    private MigrationServiceHelper helper;
    private Map<String, String> migratedIssueMap = new HashMap<String, String>();
    private Map<String, String> issueMap = new LinkedHashMap<String, String>();
    private Map<String, String> epicMap = new LinkedHashMap<String, String>();
    private Map<String, String> storyMap = new LinkedHashMap<String, String>();
    private Map<String, String> bugMap = new LinkedHashMap<String, String>();
    private Map<String, String> taskMap = new LinkedHashMap<String, String>();
    private Map<String, String> subTaskMap = new LinkedHashMap<String, String>();
    private Map<String, List<String>> epicToStoriesMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> epicToBugsMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> epicToTasksMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> storyToSubTasksMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> bugToSubTasksMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> taskToSubTasksMap = new HashMap<String, List<String>>();
    private Issue logIssue;

    private static final String RESULTS_OF_MIGRATION = "Results of migration on ";
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

    private static SimpleDateFormat commentsDateFormat = new SimpleDateFormat("dd/MMM/yyyy hh:mm aaa", Locale.US);

    private Map<String, List<IssueLinkItem>> linkMap = new HashMap<String, List<IssueLinkItem>>();

    public static void main(String args[]) {
        MigrationService service = new MigrationService();
        if (!service.init()) {
            return;
        }
        service.resetTargetProject();
        service.retrieveLogIssue();
        service.migrateEpics();
        service.migrateStories();
        service.migrateBugs();
        service.migrateTasks();
        service.migrateSubTasks();
        service.createLinks();
        service.updateLogNew();
    }

    private boolean init() {
        helper = new MigrationServiceHelper();
        return helper.init();
    }

    private void resetTargetProject() {
        if (!"true".equals(helper.getInitParams().get("target-delete-records"))) return;
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String targetProjectKey = helper.getInitParams().get("target-project-key");
        logger.info("reset target project " + targetProjectKey);
        for (int startPosition = 0;;) {
            Iterator<Issue> it = helper.getConnector().searchIssues("target", "project = " + targetProjectKey + " and creator = currentUser() order by createdDate ASC", startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                boolean success = true;
                //while (!success) {
                    try {
                        issueRestClient.deleteIssue(issue.getKey(), true).claim();
                        success = true;
                    } catch (Exception e) {
                        logger.error(e);
                    }
                //}
                logger.info("deleted issue " + issue.getKey());
            }
        }
    }

    private void migrateEpics() {
        logger.info("migration epics from project " + helper.getInitParams().get("source-project-key") + " to " + helper.getInitParams().get("target-project-key"));
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String query = "issuetype = Epic AND project =" + helper.getInitParams().get("source-project-key") + " order by createdDate ASC";
        for (int startPosition = 0;; startPosition += 50) {
            Iterator<Issue> it = helper.getConnector().searchIssues("source", query, startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                if (migratedIssueMap.containsKey(issue.getKey())) {
                    continue;
                }
                IssueField epicNameField = issue.getFieldByName("Epic Name");
                IssueInputBuilder issueBuilder = createBuilder(issue);
                issueBuilder.setFieldValue(epicNameField.getId(), epicNameField.getValue());
                IssueInput issueInput = issueBuilder.build();
                String targetKey = issueRestClient.createIssue(issueInput).claim().getKey();
                addLabels(issue, targetKey);
                addComments(issue.getKey(), targetKey);
                addAttachments(issue.getKey(), targetKey);
                setStatusAndResolution(issue, targetKey);
                epicMap.put(issue.getKey(), targetKey);
                issueMap.put(issue.getKey(), targetKey);
                logger.info("migrated Epic " + issue.getKey() + " to " + targetKey);
            }
        }
    }

    private void migrateStories() {
        logger.info("migration stories from project " + helper.getInitParams().get("source-project-key") + " to " + helper.getInitParams().get("target-project-key"));
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String query = "issuetype = Story AND project =" + helper.getInitParams().get("source-project-key") + " order by createdDate ASC";
        for (int startPosition = 0;; startPosition += 50) {
            Iterator<Issue> it = helper.getConnector().searchIssues("source", query, startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                if (migratedIssueMap.containsKey(issue.getKey())) {
                    continue;
                }
                Iterator<IssueField> it_f = issue.getFields().iterator();
                while (it_f.hasNext()) {
                    IssueField f = it_f.next();
                    //System.out.println("f = " + f.getName() + " " + f.getValue() + " " + f.getId());
                }
                IssueField epicLinkField = issue.getFieldByName("Epic Link");
                String targetIssueId = issueMap.get(epicLinkField.getValue());
                List<String> stories = epicToStoriesMap.get(epicLinkField.getValue());
                if (stories == null) {
                    stories = new ArrayList<String>();
                    epicToStoriesMap.put((String)epicLinkField.getValue(), stories);
                }
                stories.add(issue.getKey());
                IssueInputBuilder issueBuilder = createBuilder(issue);
                issueBuilder.setFieldValue(epicLinkField.getId(), targetIssueId);
                Map<String, Object> parent = new HashMap<String, Object>();
                parent.put("value", "yes");
                //FieldInput parentField = new FieldInput("Test case created", new ComplexIssueInputFieldValue(parent));
                //issueBuilder.setFieldInput(parentField);
                //issueBuilder.setFieldValue("customfield_13210", "Y");
                IssueInput issueInput = issueBuilder.build();
                String targetKey = null;
                boolean success = false;
                while (!success) {
                    try {
                        targetKey = issueRestClient.createIssue(issueInput).claim().getKey();
                        success = true;
                    } catch (Exception e) {
                        logger.error(e);
                    }
                }
                addLabels(issue, targetKey);
                addComments(issue.getKey(), targetKey);
                addAttachments(issue.getKey(), targetKey);
                setStatusAndResolution(issue, targetKey);
                setSprint(issue, targetKey);
                storyMap.put(issue.getKey(), targetKey);
                issueMap.put(issue.getKey(), targetKey);
                logger.info("migrated Story " + issue.getKey() + " to " + targetKey);
            }
        }
    }

    private void migrateBugs() {
        logger.info("migration bugs from project " + helper.getInitParams().get("source-project-key") + " to " + helper.getInitParams().get("target-project-key"));
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String query = "issuetype = Bug AND project =" + helper.getInitParams().get("source-project-key") + " order by createdDate ASC";
        for (int startPosition = 0;; startPosition += 50) {
            Iterator<Issue> it = helper.getConnector().searchIssues("source", query, startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                if (migratedIssueMap.containsKey(issue.getKey())) {
                    continue;
                }
                IssueField epicLinkField = issue.getFieldByName("Epic Link");
                String targetIssueId = issueMap.get(epicLinkField.getValue());
                List<String> bugs = epicToBugsMap.get(epicLinkField.getValue());
                if (bugs == null) {
                    bugs = new ArrayList<String>();
                    epicToBugsMap.put((String)epicLinkField.getValue(), bugs);
                }
                bugs.add(issue.getKey());
                IssueInputBuilder issueBuilder = createBuilder(issue);
                issueBuilder.setFieldValue(epicLinkField.getId(), targetIssueId);
                IssueInput issueInput = issueBuilder.build();
                String targetKey = null;
                boolean success = false;
                while (!success) {
                    try {
                        targetKey = issueRestClient.createIssue(issueInput).claim().getKey();
                        success = true;
                    } catch (Exception e) {
                        logger.error(e);
                    }
                }
                addLabels(issue, targetKey);
                addComments(issue.getKey(), targetKey);
                addAttachments(issue.getKey(), targetKey);
                setStatusAndResolution(issue, targetKey);
                setSprint(issue, targetKey);
                bugMap.put(issue.getKey(), targetKey);
                issueMap.put(issue.getKey(), targetKey);
                logger.info("migrated Bug " + issue.getKey() + " to " + targetKey);
            }
        }
    }

    private void migrateTasks() {
        logger.info("migration tasks from project " + helper.getInitParams().get("source-project-key") + " to " + helper.getInitParams().get("target-project-key"));
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String query = "issuetype = Task AND project =" + helper.getInitParams().get("source-project-key") + " order by createdDate ASC";
        for (int startPosition = 0;; startPosition += 50) {
            Iterator<Issue> it = helper.getConnector().searchIssues("source", query, startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                if (migratedIssueMap.containsKey(issue.getKey())) {
                    continue;
                }
                IssueField epicLinkField = issue.getFieldByName("Epic Link");
                String targetIssueId = issueMap.get(epicLinkField.getValue());
                List<String> tasks = epicToTasksMap.get(epicLinkField.getValue());
                if (tasks == null) {
                    tasks = new ArrayList<String>();
                    epicToTasksMap.put((String)epicLinkField.getValue(), tasks);
                }
                tasks.add(issue.getKey());
                IssueInputBuilder issueBuilder = createBuilder(issue);
                issueBuilder.setFieldValue(epicLinkField.getId(), targetIssueId);
                IssueInput issueInput = issueBuilder.build();
                String targetKey = null;
                boolean success = false;
                while (!success) {
                    try {
                        targetKey = issueRestClient.createIssue(issueInput).claim().getKey();
                        success = true;
                    } catch (Exception e) {
                        logger.error(e);
                    }
                }
                addLabels(issue, targetKey);
                addComments(issue.getKey(), targetKey);
                addAttachments(issue.getKey(), targetKey);
                setStatusAndResolution(issue, targetKey);
                setSprint(issue, targetKey);
                taskMap.put(issue.getKey(), targetKey);
                issueMap.put(issue.getKey(), targetKey);
                logger.info("migrated Task " + issue.getKey() + " to " + targetKey);
            }
        }
    }

    private void migrateSubTasks() {
        logger.info("migration sub tasks from project " + helper.getInitParams().get("source-project-key") + " to " + helper.getInitParams().get("target-project-key"));
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String query = "issuetype = Sub-Task AND project =" + helper.getInitParams().get("source-project-key") + " order by createdDate ASC";
        for (int startPosition = 0;; startPosition += 50) {
            Iterator<Issue> it = helper.getConnector().searchIssues("source", query, startPosition, 50);
            if (!it.hasNext()) break;
            while (it.hasNext()) {
                Issue issue = it.next();
                if (migratedIssueMap.containsKey(issue.getKey())) {
                    continue;
                }
                JSONObject parentFieldValue = (JSONObject)issue.getField("parent").getValue();
                String sourceParentKey = null;
                try {
                    sourceParentKey = parentFieldValue.getString("key");
                } catch (JSONException e) {
                    e.printStackTrace();
                    logger.error(e);
                    continue;
                }
                String targetParentKey = issueMap.get(sourceParentKey);
                if (targetParentKey == null) continue;
                IssueInputBuilder issueBuilder = createBuilder(issue);
                Map<String, Object> parent = new HashMap<String, Object>();
                parent.put("key", targetParentKey);
                FieldInput parentField = new FieldInput("parent", new ComplexIssueInputFieldValue(parent));
                issueBuilder.setFieldInput(parentField);
                IssueInput issueInput = issueBuilder.build();
                String targetKey = null;
                boolean success = false;
                while (!success) {
                    try {
                        targetKey = issueRestClient.createIssue(issueInput).claim().getKey();
                        success = true;
                    } catch (Exception e) {
                        logger.error(e);
                    }
                }
                addLabels(issue, targetKey);
                addAttachments(issue.getKey(), targetKey);
                addComments(issue.getKey(), targetKey);
                setStatusAndResolution(issue, targetKey);
                setSprint(issue, targetKey);
                subTaskMap.put(issue.getKey(), targetKey);
                issueMap.put(issue.getKey(), targetKey);
                if (storyMap.containsKey(sourceParentKey)) {
                    List<String> subTasks = storyToSubTasksMap.get(sourceParentKey);
                    if (subTasks == null) {
                        subTasks = new ArrayList<String>();
                        storyToSubTasksMap.put(sourceParentKey, subTasks);
                    }
                    subTasks.add(issue.getKey());
                } else if (bugMap.containsKey(sourceParentKey)) {
                    List<String> subTasks = bugToSubTasksMap.get(sourceParentKey);
                    if (subTasks == null) {
                        subTasks = new ArrayList<String>();
                        bugToSubTasksMap.put(sourceParentKey, subTasks);
                    }
                    subTasks.add(issue.getKey());
                } else if (taskMap.containsKey(sourceParentKey)) {
                    List<String> subTasks = taskToSubTasksMap.get(sourceParentKey);
                    if (subTasks == null) {
                        subTasks = new ArrayList<String>();
                        taskToSubTasksMap.put(sourceParentKey, subTasks);
                    }
                    subTasks.add(issue.getKey());
                }
                logger.info("migrated Sub-Task " + issue.getKey() + " to " + targetKey);
            }
        }
    }

    private IssueInputBuilder createBuilder(Issue issue) {
        IssueInputBuilder issueBuilder = new IssueInputBuilder();
        issueBuilder.setSummary(issue.getSummary());
        issueBuilder.setDescription(issue.getDescription());
        issueBuilder.setPriority(issue.getPriority());
        issueBuilder.setProjectKey(helper.getInitParams().get("target-project-key"));
        issueBuilder.setIssueTypeId(helper.getTargetIssueTypeMap().get(issue.getIssueType().getName()));
        String targetUserName = issue.getAssignee() != null ? helper.getUserMap().get(issue.getAssignee().getName()) : null;
        if (targetUserName != null) {
            //issueBuilder.setAssigneeName(targetUserName);
        }
        targetUserName = issue.getReporter() != null ? helper.getUserMap().get(issue.getReporter().getName()) : null;
        if (targetUserName != null) {
            //issueBuilder.setReporterName(targetUserName);
        }
        if (!issue.getIssueType().getName().equals("Epic")) {
            Iterator<Version> it = issue.getAffectedVersions().iterator();
            Set<String> names = new LinkedHashSet<String>();
            while (it.hasNext()) {
                String sourceVersionName = it.next().getName();
                if (helper.getVersionMap().containsKey(sourceVersionName)) {
                    names.add(sourceVersionName);
                }
            }
            if (!names.isEmpty()) {
                issueBuilder.setAffectedVersionsNames(names);
            }
        }
        Iterator<Version> it = issue.getFixVersions().iterator();
        Set<String> names = new LinkedHashSet<String>();
        while (it.hasNext()) {
            String sourceVersionName = it.next().getName();
            if (helper.getVersionMap().containsKey(sourceVersionName)) {
                names.add(sourceVersionName);
            }
        }
        if (!names.isEmpty()) {
            issueBuilder.setFixVersionsNames(names);
        }
        return issueBuilder;
    }

    private void addLabels(Issue sourceIssue, String targetIssueKey) {
        Set<String> labels = sourceIssue.getLabels();
        if (!labels.isEmpty()) {
            try {
                JSONObject jsonBody = new JSONObject();
                JSONObject jsonLabelSection = new JSONObject();
                JSONArray jsonLabels = new JSONArray();
                int index = 0;
                for (String label : labels) {
                    JSONObject jsonLabel = new JSONObject();
                    jsonLabel.put("add", label);
                    jsonLabels.put(index++, jsonLabel);
                }
                jsonLabelSection.put("labels", jsonLabels);
                jsonBody.put("update", jsonLabelSection);
                boolean success = false;
                while (!success) {
                    try {
                        helper.getConnector().restPutJson("target",
                                helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + targetIssueKey, jsonBody.toString());
                        success = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (JSONException e) {
                logger.error(e);
            }
        }
    }

    private void addComments(String sourceIssueKey, String targetIssueKey) {
        JSONObject jsonCommentsBody = helper.getConnector()
                .restGetJson("source", helper.getInitParams()
                        .get("source-jira-url") + "/rest/api/2/issue/" + sourceIssueKey + "/comment");
        if (jsonCommentsBody == null) return;
        List<String> comments = new ArrayList<String>();
        try {
            JSONArray jsonComments = jsonCommentsBody.getJSONArray("comments");
            for (int i = 0; i < jsonComments.length(); i++) {
                JSONObject jsonComment = (JSONObject) jsonComments.get(i);
                JSONObject jsonAuthor = (JSONObject) jsonComment.get("author");
                String body = jsonComment.getString("body");
                String displayName = jsonAuthor.getString("displayName");
                String created = jsonComment.getString("created");
                String parts[] = created.split("\\+");
                created = parts[0];
                LocalDateTime createdDate = LocalDateTime.parse(created);
                Timestamp ts = java.sql.Timestamp.valueOf(createdDate);
                String formattedDate = commentsDateFormat.format(ts);
                comments.add("*" + displayName + " " + formattedDate + "*\n\n" + body);
            }
        } catch (JSONException e) {
            logger.error(e);
        }
        for (String comment : comments) {
            boolean success = false;
            while (!success) {
                try {
                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("body", comment);
                    helper.getConnector().restPostJsonNative("target",
                            helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + targetIssueKey + "/comment", comment);
                    success = true;
                } catch (Exception e) {
                    logger.error(e);
                }
            }
        }
    }

    private void addAttachments(String sourceIssueKey, String targetIssueKey) {
        List<String> ids = new ArrayList<String>();
        JSONObject jsonBody = helper.getConnector()
                .restGetJson("source", helper.getInitParams()
                        .get("source-jira-url") + "/rest/api/2/issue/" + sourceIssueKey);
        if (jsonBody == null) return;
        try {
            JSONObject jfields = jsonBody.getJSONObject("fields");
            JSONArray attachments = jfields.getJSONArray("attachment");
            for (int i = 0; i < attachments.length(); i++) {
                ids.add(attachments.getJSONObject(i).getString("id"));
            }
        } catch (Exception e) {
            logger.error(e);
        }
        for (String id : ids) {
            jsonBody = helper.getConnector()
                    .restGetJson("source", helper.getInitParams()
                            .get("source-jira-url") + "/rest/api/2/attachment/" + id);
            if (jsonBody == null) continue;
            try {
                String name = jsonBody.getString("filename");
                String path = jsonBody.getString("content");
                byte content [] = helper.getConnector()
                        .restGet("source", path);
                if (content == null) {
                    logger.error("attachment reference is dead : " + path);
                    continue;
                }
                helper.getConnector().restPostFile("target",
                        helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + targetIssueKey + "/attachments", content, name);
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }

    private void setStatusAndResolution(Issue sourceIssue, String targetIssueKey) {
        JSONObject jsonBody = helper.getConnector()
                .restGetJson("source", helper.getInitParams()
                        .get("source-jira-url") + "/rest/api/2/issue/" + sourceIssue.getKey() + "?fields=status&fields=resolution&fields=issuelinks");
        try {
            JSONObject jsonFields = jsonBody.getJSONObject("fields");
            //JSONObject jsonResolution = jsonFields.getJSONObject("resolution");
            JSONArray jsonLinks = jsonFields.getJSONArray("issuelinks");
            List<IssueLinkItem> issueLinkItems = null;
            for (int i = 0; i < jsonLinks.length(); i ++) {
                if (issueLinkItems == null) {
                    issueLinkItems = new ArrayList<IssueLinkItem>();
                    linkMap.put(targetIssueKey, issueLinkItems);
                }
                JSONObject jsonLink = jsonLinks.getJSONObject(i);
                JSONObject jsonLinkType= jsonLink.getJSONObject("type");
                String direction = jsonLink.has("inwardIssue") ? "inwardIssue" : "outwardIssue";
                JSONObject jsonIssueLink = jsonLink.getJSONObject(direction);
                String issueLinkKey = jsonIssueLink.getString("key");
                IssueLinkItem issueLinkItem = new IssueLinkItem(jsonLinkType.getString("name"), jsonLinkType.getString("inward"),
                        jsonLinkType.getString("outward"), issueLinkKey, direction);
                issueLinkItems.add(issueLinkItem);
            }
            JSONObject jsonStatus = jsonFields.getJSONObject("status");
            if (jsonStatus != null) {
                Integer transitionId = helper.getTransitionId(jsonStatus.getString("name"), sourceIssue.getIssueType().getId(), targetIssueKey);
                if (transitionId < 0) return;
                //System.out.println("tranId = " + transitionId);
                if (transitionId != null) {
                    jsonBody = new JSONObject();
                    /*JSONObject jsonResolutionSection = new JSONObject();
                    JSONArray jsonResolutions = new JSONArray();
                    JSONObject jsonResolutionTarget = new JSONObject();
                    jsonResolutionTarget.put("add", jsonResolution.getString("name"));
                    jsonResolutions.put(0, jsonResolutionTarget);
                    jsonResolutionSection.put("resolution", jsonResolutions);
                    jsonBody.put("update", jsonResolutionSection);*/
                    JSONObject jsonTransition = new JSONObject();
                    jsonTransition.put("id", transitionId);
                    jsonBody.put("transition", jsonTransition);
                    helper.getConnector().restPostJson("target",
                            helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + targetIssueKey + "/transitions", jsonBody.toString());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e);
        }
    }

    private void setSprint(Issue sourceIssue, String targetIssueKey) {
        IssueField issueField = sourceIssue.getFieldByName("Sprint");
        if (issueField != null) {
            try {
                JSONArray jsonSprints = (JSONArray) issueField.getValue();
                if (jsonSprints == null) return;
                for (int i = 0; i < jsonSprints.length(); i++) {
                    String jsonSprint = jsonSprints.getString(i);
                    int position = jsonSprint.indexOf("name=");
                    if (position < 0) continue;
                    int endPosition = jsonSprint.indexOf(",", position);
                    String sprintName = jsonSprint.substring(position + 5, endPosition);
                    String sprintId = helper.getSprintMap().get(sprintName);
                    if (sprintId != null) {
                        JSONObject jsonBody = new JSONObject();
                        JSONArray jsonIssues = new JSONArray();
                        jsonIssues.put(0, targetIssueKey);
                        jsonBody.put("issues", jsonIssues);
                        helper.getConnector().restPostJson("target",
                                helper.getInitParams().get("target-jira-url") + "/rest/agile/1.0/sprint/" + sprintId + "/issue", jsonBody.toString());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error(e);
            }
        }
    }

    private void retrieveLogIssue() {
        logger.info("retrieving log issue ...");
        try {
            Iterator<Issue> it = helper.getConnector().getJiraRestClient("target").getSearchClient()
                    .searchJql("issuetype = 'New Feature' AND project = " + helper.getInitParams().get("target-project-key")).get().getIssues().iterator();
            while (it.hasNext()) {
                logIssue = it.next();
                if (logIssue.getDescription().startsWith(RESULTS_OF_MIGRATION)) {
                    logger.info("Found log issue = " + logIssue.getSummary());
                    String lines [] = logIssue.getDescription().split("\n");
                    for (String line : lines) {
                        int pos = line.indexOf(helper.getInitParams().get("source-project-key"));
                        if (pos >= 0) {
                            String sourceIssueKey = line.substring(pos, line.indexOf("|", pos));
                            pos = line.indexOf(helper.getInitParams().get("target-project-key"));
                            String targetIssueKey = line.substring(pos, line.indexOf("|", pos));
                            migratedIssueMap.put(sourceIssueKey, targetIssueKey);
                            issueMap.put(sourceIssueKey, targetIssueKey);
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateLog() {
        logger.info("Creating log...");
        String sourceJiraUrl = helper.getInitParams().get("source-jira-url");
        String targetJiraUrl = helper.getInitParams().get("source-jira-url");
        StringBuilder sb = new StringBuilder();
        sb.append(RESULTS_OF_MIGRATION + dateFormat.format(System.currentTimeMillis()) + "\n");
        for (Map.Entry<String, String> entry : epicMap.entrySet()) {
            sb.append("Migrated Epic *[" + entry.getKey() + "|" + sourceJiraUrl + "/browse/" + entry.getKey() + "]* to " +
                    "*[" + entry.getValue() + "|" + targetJiraUrl + "/browse/" + entry.getValue() + "]*\n");
            List<String> stories = epicToStoriesMap.get(entry.getKey());
            if (stories != null) {
                for (String storyKey : stories) {
                    sb.append("&emsp;&emsp;Migrated Story *[" + storyKey + "|" + sourceJiraUrl + "/browse/" + storyKey + "]* to " +
                            "*[" + storyMap.get(storyKey) + "|" + targetJiraUrl + "/browse/" + storyMap.get(storyKey) + "]*\n");
                    List<String> subTasks = storyToSubTasksMap.get(storyKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
            List<String> bugs = epicToBugsMap.get(entry.getKey());
            if (bugs != null) {
                for (String bugKey : bugs) {
                    sb.append("&emsp;&emsp;Migrated Bug *[" + bugKey + "|" + sourceJiraUrl + "/browse/" + bugKey + "]* to " +
                            "*[" + bugMap.get(bugKey) + "|" + targetJiraUrl + "/browse/" + bugMap.get(bugKey) + "]*\n");
                    List<String> subTasks = bugToSubTasksMap.get(bugKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
            List<String> tasks = epicToTasksMap.get(entry.getKey());
            if (tasks != null) {
                for (String taskKey : tasks) {
                    sb.append("&emsp;&emsp;Migrated Task *[" + taskKey + "|" + sourceJiraUrl + "/browse/" + taskKey + "]* to " +
                            "*[" + taskMap.get(taskKey) + "|" + targetJiraUrl + "/browse/" + taskMap.get(taskKey) + "]*\n");
                    List<String> subTasks = taskToSubTasksMap.get(taskKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
        }
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String targetProjectKey = helper.getInitParams().get("target-project-key");
        if (logIssue == null) {
            IssueInputBuilder issueBuilder = new IssueInputBuilder(targetProjectKey, 2L);
            issueBuilder.setSummary("Migration Log");
            issueBuilder.setDescription(sb.toString());
            IssueInput issueInput = issueBuilder.build();
            String key = issueRestClient.createIssue(issueInput).claim().getKey();
            logger.info("log issue " + key + " has been created");
        } else {
            IssueInputBuilder issueBuilder = new IssueInputBuilder(targetProjectKey, 2L);
            issueBuilder.setDescription(logIssue.getDescription() + sb.toString());
            issueRestClient.updateIssue(logIssue.getKey(), issueBuilder.build()).claim();
            logger.info("log issue " + logIssue.getKey() + " has been updated");
        }
    }

    private void updateLogNew() {
        logger.info("Creating log...");
        String sourceJiraUrl = helper.getInitParams().get("source-jira-url");
        String targetJiraUrl = helper.getInitParams().get("source-jira-url");
        StringBuilder sb = new StringBuilder();
        sb.append(RESULTS_OF_MIGRATION + dateFormat.format(System.currentTimeMillis()) + "\n");
        IssueRestClient issueRestClient = helper.getConnector().getJiraRestClient("target").getIssueClient();
        String targetProjectKey = helper.getInitParams().get("target-project-key");
        String logIssueKey = null;
        if (logIssue == null) {
            IssueInputBuilder issueBuilder = new IssueInputBuilder(targetProjectKey, 2L);
            issueBuilder.setSummary("Migration Log");
            issueBuilder.setDescription(sb.toString());
            IssueInput issueInput = issueBuilder.build();
            logIssueKey = issueRestClient.createIssue(issueInput).claim().getKey();
            logger.info("log issue " + logIssueKey + " has been created");
        } else {
            IssueInputBuilder issueBuilder = new IssueInputBuilder(targetProjectKey, 2L);
            issueBuilder.setDescription(logIssue.getDescription() + sb.toString());
            issueRestClient.updateIssue(logIssue.getKey(), issueBuilder.build()).claim();
            logger.info("log issue " + logIssue.getKey() + " has been updated");
        }
        for (Map.Entry<String, String> entry : epicMap.entrySet()) {
            sb = new StringBuilder();
            sb.append("Migrated Epic *[" + entry.getKey() + "|" + sourceJiraUrl + "/browse/" + entry.getKey() + "]* to " +
                    "*[" + entry.getValue() + "|" + targetJiraUrl + "/browse/" + entry.getValue() + "]*\n");
            List<String> stories = epicToStoriesMap.get(entry.getKey());
            if (stories != null) {
                for (String storyKey : stories) {
                    sb.append("&emsp;&emsp;Migrated Story *[" + storyKey + "|" + sourceJiraUrl + "/browse/" + storyKey + "]* to " +
                            "*[" + storyMap.get(storyKey) + "|" + targetJiraUrl + "/browse/" + storyMap.get(storyKey) + "]*\n");
                    List<String> subTasks = storyToSubTasksMap.get(storyKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
            List<String> bugs = epicToBugsMap.get(entry.getKey());
            if (bugs != null) {
                for (String bugKey : bugs) {
                    sb.append("&emsp;&emsp;Migrated Bug *[" + bugKey + "|" + sourceJiraUrl + "/browse/" + bugKey + "]* to " +
                            "*[" + bugMap.get(bugKey) + "|" + targetJiraUrl + "/browse/" + bugMap.get(bugKey) + "]*\n");
                    List<String> subTasks = bugToSubTasksMap.get(bugKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
            List<String> tasks = epicToTasksMap.get(entry.getKey());
            if (tasks != null) {
                for (String taskKey : tasks) {
                    sb.append("&emsp;&emsp;Migrated Task *[" + taskKey + "|" + sourceJiraUrl + "/browse/" + taskKey + "]* to " +
                            "*[" + taskMap.get(taskKey) + "|" + targetJiraUrl + "/browse/" + taskMap.get(taskKey) + "]*\n");
                    List<String> subTasks = taskToSubTasksMap.get(taskKey);
                    if (subTasks != null) {
                        for (String subTaskKey : subTasks) {
                            sb.append("&emsp;&emsp;&emsp;&emsp;Migrated Sub-Task *[" + subTaskKey + "|" + sourceJiraUrl + "/browse/" + subTaskKey + "]* to " +
                                    "*[" + subTaskMap.get(subTaskKey) + "|" + targetJiraUrl + "/browse/" + subTaskMap.get(subTaskKey) + "]*\n");
                        }
                    }
                }
            }
            boolean success = false;
            while (!success) {
                JSONObject jsonBody = new JSONObject();
                try {
                    jsonBody.put("body", sb.toString());
                    helper.getConnector().restPostJson("target",
                            helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + logIssueKey + "/comment", jsonBody.toString());
                    success = true;
                } catch (Exception e) {
                    logger.error(e);
                }
            }
        }
    }

    private void createLink() {
        JSONObject jsonUpdate = new JSONObject();
        JSONObject jsonLinkType = new JSONObject();
        JSONObject jsonOutwardIssue = new JSONObject();
        JSONObject jsonLink = new JSONObject();
        JSONObject jsonOperation = new JSONObject();
        JSONArray jsonLinksArray = new JSONArray();
        JSONObject jsonLinks = new JSONObject();
        try {
            jsonLinkType.put("name", "Blocks");
            jsonLinkType.put("inward", "is blocked by");
            jsonLinkType.put("outward", "blocks");
            jsonOutwardIssue.put("key", "OTFM-2951");
            jsonLink.put("type", jsonLinkType);
            jsonLink.put("inwardIssue", jsonOutwardIssue);
            jsonOperation.put("add", jsonLink);
            jsonLinksArray.put(0, jsonOperation);
            jsonLinks.put("issuelinks", jsonLinksArray);
            jsonUpdate.put("update", jsonLinks);
            System.out.println("jj = " + jsonUpdate.toString());
            helper.getConnector().restPutJson("target",
                    helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/OTFM-2934", jsonUpdate.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void createLinks() {
        try {
            for (Map.Entry<String, List<IssueLinkItem>> entry : linkMap.entrySet()) {
                System.out.println("link to issue " + entry.getKey());
                List<IssueLinkItem> issueLinkItems = entry.getValue();
                for (int i = 0; i < issueLinkItems.size(); i++) {
                    IssueLinkItem item = issueLinkItems.get(i);
                    String targetLinkedIssueKey = issueMap.get(item.linkIssueKey);
                    if (targetLinkedIssueKey == null) continue;
                    JSONObject jsonUpdate = new JSONObject();
                    JSONArray jsonLinksArray = new JSONArray();
                    JSONObject jsonLinks = new JSONObject();
                    JSONObject jsonLinkType = new JSONObject();
                    JSONObject jsonIssue = new JSONObject();
                    JSONObject jsonLink = new JSONObject();
                    JSONObject jsonOperation = new JSONObject();
                    System.out.println("\t" + item.name + " " + item.inward + " " + item.outward + " " + item.direction + " " + item.linkIssueKey);
                    jsonLinkType.put("name", item.name);
                    jsonLinkType.put("inward", item.inward);
                    jsonLinkType.put("outward", item.outward);
                    jsonIssue.put("key", targetLinkedIssueKey);
                    jsonLink.put("type", jsonLinkType);
                    jsonLink.put(item.direction, jsonIssue);
                    jsonOperation.put("add", jsonLink);
                    jsonLinksArray.put(0, jsonOperation);
                    jsonLinks.put("issuelinks", jsonLinksArray);
                    jsonUpdate.put("update", jsonLinks);
                    System.out.println("body = " + jsonUpdate.toString());
                    boolean success = false;
                    while (!success) {
                        try {
                            helper.getConnector().restPutJson("target",
                                    helper.getInitParams().get("target-jira-url") + "/rest/api/2/issue/" + entry.getKey(), jsonUpdate.toString());
                            success = true;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (JSONException e) {
            logger.error(e);
        }
    }
}