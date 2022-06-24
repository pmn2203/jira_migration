package com.customertimes.jira.client;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

public class MigrationServiceHelper {
    private Logger logger = Logger.getLogger(MigrationService.class);

    private MigrationServiceConnector connector;
    private Map<String, String> initParams = new HashMap<String, String>();
    private Map<String, String> userMap = new HashMap<String, String>();
    private Map<String, String> versionMap = new HashMap<String, String>();
    private Map<String, String> statusMap = new HashMap<String, String>();
    private Map<String, String> sprintMap = new HashMap<String, String>();
    private Map <String, Long> targetIssueTypeMap = new HashMap<String, Long>();
    private Map <String, Integer> transitionIdsMap = new HashMap<String, Integer>();

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public MigrationServiceConnector getConnector() {
        return connector;
    }

    public Map <String, Long> getTargetIssueTypeMap() {
        return targetIssueTypeMap;
    }

    public Map<String, String> getUserMap() {
        return userMap;
    }

    public Map<String, String> getVersionMap() {
        return versionMap;
    }

    public Map<String, String> getSprintMap() {
        return sprintMap;
    }

    public Integer getTransitionId(String status, Long issueTypeId, String targetIssueKey) {
        Integer transitionId = transitionIdsMap.get(status + "_" + issueTypeId);
        if (transitionId == null) {
            IssueRestClient issueRestClient = connector.getJiraRestClient("target").getIssueClient();
            try {
                Issue targetIssue = issueRestClient.getIssue(targetIssueKey).get();
                Iterator<Transition> it = issueRestClient.getTransitions(targetIssue).get().iterator();
                while (it.hasNext()) {
                    Transition transition = it.next();
                    if (transition.getName().equals(status)) {
                        transitionId = transition.getId();
                        transitionIdsMap.put(status + "_" + issueTypeId, transitionId);
                    }
                }
                if (transitionId == null) {
                    transitionId = -1;
                    transitionIdsMap.put(status + "_" + issueTypeId, transitionId);
                }
            } catch (Exception e) {
                logger.error(e);
            }
        }
        return transitionId;
    }

    public boolean init() {
        Map<String, String> sprintMapping = new HashMap<String, String>();
        try {
            InputStream is = System.getProperty("config.file.path") != null ? new FileInputStream(System.getProperty("config.file.path")) :
                    getClass().getClassLoader().getResourceAsStream("config.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(is);
            Node root = document.getFirstChild();
            NodeList nodes = root.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeName().equals("source") || node.getNodeName().equals("target")) {
                    initCommonParams(node);
                } else if (node.getNodeName().equals("users-mapping")) {
                    NodeList mappingNodes = node.getChildNodes();
                    for (int j = 0; j < mappingNodes.getLength(); j ++) {
                        Node nodeUser = mappingNodes.item(j);
                        if (nodeUser.getNodeName().equals("user")) {
                            NamedNodeMap attrs = nodeUser.getAttributes();
                            userMap.put(attrs.getNamedItem("source").getNodeValue(), attrs.getNamedItem("target").getNodeValue());
                        }
                    }
                } else if (node.getNodeName().equals("status-mapping")) {
                    NodeList mappingNodes = node.getChildNodes();
                    for (int j = 0; j < mappingNodes.getLength(); j ++) {
                        Node nodeUser = mappingNodes.item(j);
                        if (nodeUser.getNodeName().equals("status")) {
                            NamedNodeMap attrs = nodeUser.getAttributes();
                            statusMap.put(attrs.getNamedItem("source").getNodeValue(), attrs.getNamedItem("target").getNodeValue());
                        }
                    }
                } else if (node.getNodeName().equals("sprint-mapping")) {
                    NodeList mappingNodes = node.getChildNodes();
                    for (int j = 0; j < mappingNodes.getLength(); j ++) {
                        Node nodeUser = mappingNodes.item(j);
                        if (nodeUser.getNodeName().equals("sprint")) {
                            NamedNodeMap attrs = nodeUser.getAttributes();
                            sprintMapping.put(attrs.getNamedItem("target").getNodeValue(), attrs.getNamedItem("source").getNodeValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("initialization failed ", e);
            return false;
        }
        logger.info("------ statuses mapping start ------");
        for (Map.Entry<String, String> entry : statusMap.entrySet()) {
            logger.info("status <" + entry.getKey() + "> will map to <" + entry.getValue() + ">");
        }
        logger.info("------ statuses mapping end ------");

        logger.info("------ sprints mapping start ------");
        for (Map.Entry<String, String> entry : sprintMapping.entrySet()) {
            logger.info("sprint <" + entry.getKey() + "> will map to <" + entry.getValue() + ">");
        }
        logger.info("------ sprints mapping end ------");

        connector = new MigrationServiceConnector(initParams);
        connector.init();
        JSONObject jsonRoles = connector.restGetJson("target", initParams.get("target-jira-url") + "/rest/api/2/project/" + initParams.get("target-project-key") + "/role");
        try {
            Iterator it = jsonRoles.keys();
            String projectRoleUrl = null;
            while (it.hasNext()) {
                String key = (String) it.next();
                if (key.equals("Users")) {
                    projectRoleUrl = (String) jsonRoles.get(key);
                }
            }
            JSONObject jsonProjectRoles = connector.restGetJson("target", projectRoleUrl);
            JSONArray jsonProjectActors = jsonProjectRoles.getJSONArray("actors");
            Map<String, String> filteredUserMap = new HashMap<String, String>();
            Set<String> targetUserNames = new HashSet<String>();
            for (int i = 0; i < jsonProjectActors.length(); i ++) {
                targetUserNames.add(jsonProjectActors.getJSONObject(i).getString("name"));
            }
            for (Map.Entry<String, String> entry : userMap.entrySet()) {
                if (targetUserNames.contains(entry.getValue())) {
                    filteredUserMap.put(entry.getKey(), entry.getValue());
                }
            }
            userMap = filteredUserMap;
            logger.info("------ users mapping start ------");
            for (Map.Entry<String, String> entry : userMap.entrySet()) {
                logger.info("user <" + entry.getKey() + "> will map to <" + entry.getValue() + ">");
            }
            logger.info("------ users mapping end ------");
        } catch (JSONException e) {
            logger.error(e);
            return false;
        }
        try {
            Iterator<IssueType> it = connector.getJiraRestClient("target").getMetadataClient().getIssueTypes().get().iterator();
            while (it.hasNext()) {
                IssueType issueType = it.next();
                targetIssueTypeMap.put(issueType.getName(), issueType.getId());
            }
        } catch (Exception e) {
            logger.error(e);
            return false;
        }
        JSONObject jsonVersions = connector.restGetJson("target", initParams.get("target-jira-url") + "/rest/api/2/project/" + initParams.get("target-project-key") + "/version");
        try {
            JSONArray jsonVersionValues = jsonVersions.getJSONArray("values");
            for (int i = 0; i < jsonVersionValues.length(); i ++) {
                String versionName = jsonVersionValues.getJSONObject(i).getString("name");
                versionMap.put(versionName, versionName);
            }
        } catch (JSONException e) {
            logger.error(e);
            return false;
        }
        JSONObject jsonSprints = connector.restGetJson("target", initParams.get("target-jira-url") + "/rest/agile/1.0/board/" + initParams.get("target-board-id") + "/sprint");
        try {
            JSONArray jsonSprintValues = jsonSprints.getJSONArray("values");
            for (int i = 0; i < jsonSprintValues.length(); i ++) {
                String targetSprintName = jsonSprintValues.getJSONObject(i).getString("name");
                String sourceSprintName = sprintMapping.get(targetSprintName);
                if (sourceSprintName != null) {
                    String sprintId = jsonSprintValues.getJSONObject(i).getString("id");
                    sprintMap.put(sourceSprintName, sprintId);
                }
            }
        } catch (JSONException e) {
            logger.error(e);
            return false;
        }
        return true;
    }

    private void initCommonParams(Node node) {
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i ++) {
            Node childNode = nodes.item(i);
            if (!childNode.getNodeName().equals("#text")) {
                initParams.put(node.getNodeName() + "-" + childNode.getNodeName(), childNode.getTextContent());
            }
        }
    }
}
