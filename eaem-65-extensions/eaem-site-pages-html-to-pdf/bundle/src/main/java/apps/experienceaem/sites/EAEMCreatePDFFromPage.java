package apps.experienceaem.sites;

import com.adobe.granite.workflow.PayloadMap;
import com.day.cq.commons.jcr.JcrUtil;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.commons.util.DamUtil;
import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.metadata.MetaDataMap;
import org.apache.commons.exec.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.*;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowProcess;
import org.apache.sling.jcr.resource.api.JcrResourceConstants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.*;

@Component(
        immediate = true,
        service = {WorkflowProcess.class},
        property = {
                "process.label = Experience AEM Create PDF From page"
        }
)
public class EAEMCreatePDFFromPage implements WorkflowProcess {
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private static String ARG_COMMAND = "COMMAND";
    private static Integer CMD_TIME_OUT = 300000; // 5 minutes
    private static SimpleDateFormat PDF_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
    private static String AGENT_PUBLISH = "publish";
    private static String DAM_FOLDER_PREFIX = "/content/dam";
    private static int RETRIES = 20;
    private static int SLEEP_TIME = 5000; // wait 5 secs

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private AgentManager agentMgr = null;

    @Override
    public void execute(WorkItem workItem, WorkflowSession wfSession, MetaDataMap args) throws WorkflowException {
        File tmpDir = null;

        try {
            Session session = wfSession.getSession();
            WorkflowData wfData = workItem.getWorkflowData();

            String pagePath = null;
            String payLoadType = wfData.getPayloadType();

            if(payLoadType.equals("JCR_PATH") && wfData.getPayload() != null) {
                if(session.itemExists((String)wfData.getPayload())) {
                    pagePath = (String)wfData.getPayload();
                }
            } else if( (wfData.getPayload() != null) && payLoadType.equals("JCR_UUID")) {
                Node metaDataMap = session.getNodeByUUID((String)wfData.getPayload());
                pagePath = metaDataMap.getPath();
            }

            if(StringUtils.isEmpty(pagePath)){
                log.warn("Page path - " + wfData.getPayload() + ", does not exist");
                return;
            }

            ResourceResolver resolver = getResourceResolver(session);
            Resource pageResource = resolver.getResource(pagePath);

            tmpDir = File.createTempFile("eaem", null);
            tmpDir.delete();
            tmpDir.mkdir();

            File tmpFile = new File(tmpDir, pageResource.getName() + "-" + PDF_DATE_FORMAT.format(new Date()) + ".pdf");
            CommandLine commandLine = getCommandLine(pagePath, args, tmpFile);
            int count = RETRIES;

            do{
                Thread.sleep(SLEEP_TIME);

                if( !wasPageReplicatedRecently(pageResource) ){
                    log.debug("Page - " + pageResource.getPath() + ", not replicated, skipping PDF generation");
                    continue;
                }

                executeCommand(commandLine);

                createAssetInDAM(pagePath, tmpFile, resolver);

                session.save();

                break;
            }while(count-- > 0);
        } catch (Exception e) {
            log.error("Failed to create PDF of page", e);
        }finally{
            if(tmpDir != null){
                try { FileUtils.deleteDirectory(tmpDir); } catch(Exception ignore){}
            }
        }
    }

    private boolean wasPageReplicatedRecently(Resource pageResource){
        ValueMap valueMap = pageResource.getChild("jcr:content").getValueMap();
        Date lastReplicated = valueMap.get("cq:lastReplicated", Date.class);

        if(lastReplicated == null){
            return false;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        cal.add(Calendar.HOUR, -1);

        Date oneHourBack = cal.getTime();

        //check if the page was replicated in last 60 minutes
        return lastReplicated.getTime() > oneHourBack.getTime();
    }

    private void createAssetInDAM(String pagePath, File tmpFile, ResourceResolver resolver)throws Exception{
        String rootPath = DAM_FOLDER_PREFIX + pagePath;
        String assetPath = rootPath + "/" + tmpFile.getName();

        JcrUtil.createPath(DAM_FOLDER_PREFIX + pagePath, "sling:Folder", "sling:Folder",
                                    resolver.adaptTo(Session.class), true);

        AssetManager assetManager = resolver.adaptTo(AssetManager.class);

        FileInputStream fileIn = new FileInputStream(tmpFile);

        Asset asset = assetManager.createAsset(assetPath, fileIn, "application/pdf", false);

        log.info("Created asset - " + asset.getPath() + ", for published page - " + pagePath);

        IOUtils.closeQuietly(fileIn);
    }

    private CommandLine getCommandLine(String pagePath, MetaDataMap args, File tmpFile) throws Exception{
        String processArgs = args.get("PROCESS_ARGS", String.class);

        if(StringUtils.isEmpty(processArgs)){
            throw new RuntimeException("No command available in process args");
        }

        String[] arguments = processArgs.split(",");
        String command = null;

        for(String argument : arguments){
            String[] params = argument.split("=");

            if(params[0].trim().equals(ARG_COMMAND)){
                command = params[1].trim();
                break;
            }
        }

        if(StringUtils.isEmpty(command)){
            throw new RuntimeException("No command available in process args");
        }

        HashMap<String, String> parameters = new HashMap<String, String>();
        parameters.put("publishPagePath", getPublishPath(pagePath));
        parameters.put("timeStampedPDFInAssets", tmpFile.getAbsolutePath());

        return CommandLine.parse(command, parameters);
    }

    private String getPublishPath(String pagePath){
        Agent publishAgent = agentMgr.getAgents().get(AGENT_PUBLISH);

        String transportURI = publishAgent.getConfiguration().getTransportURI();

        String hostName = transportURI.substring(0, transportURI.indexOf("/bin/receive"));

        return ( hostName + pagePath + ".html");
    }

    private void executeCommand(CommandLine commandLine) throws Exception{
        DefaultExecutor exec = new DefaultExecutor();
        ExecuteWatchdog watchDog = new ExecuteWatchdog(CMD_TIME_OUT);

        exec.setWatchdog(watchDog);
        exec.setStreamHandler(new PumpStreamHandler(System.out, System.err));
        exec.setProcessDestroyer(new ShutdownHookProcessDestroyer());

        int exitValue = exec.execute(commandLine);

        if(exec.isFailure(exitValue)){
            throw new RuntimeException("Error creating PDF, command returned - " + exitValue);
        }
    }

    private ResourceResolver getResourceResolver(final Session session) throws LoginException {
        return resolverFactory.getResourceResolver( Collections.<String, Object>
                singletonMap(JcrResourceConstants.AUTHENTICATION_INFO_SESSION, session));
    }
}
