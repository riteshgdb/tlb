package tlb.service;

import tlb.TlbConstants;

import static tlb.TlbConstants.*;

import tlb.domain.SuiteResultEntry;
import tlb.domain.SuiteTimeEntry;
import tlb.service.http.HttpAction;
import tlb.service.http.DefaultHttpAction;
import tlb.storage.TlbEntryRepository;
import tlb.utils.FileUtil;
import tlb.utils.SystemEnvironment;
import tlb.utils.XmlUtil;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.dom4j.Attribute;
import org.dom4j.Element;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @understands requesting and posting information to/from cruise
 */
public class TalkToGoServer extends SmoothingTalkToService {
    private static final Logger logger = Logger.getLogger(TalkToGoServer.class.getName());

    private final HttpAction httpAction;
    private static final String JOB_NAME = "name";
    protected static final String TEST_TIME_FILE = "tlb/test_time.properties";
    private static final Pattern STAGE_LOCATOR = Pattern.compile("(.*?)/\\d+/(.*?)/\\d+");
    private Integer subsetSize;
    final String jobLocator;
    final String stageLocator;
    public static final String FAILED_TESTS_FILE = "tlb/failed_tests";

    private static final String INT = "\\d+";
    private static final Pattern NUMBER_BASED_LOAD_BALANCED_JOB = Pattern.compile("(.*?)-(" + INT + ")");
    private static final String HEX = "[a-fA-F0-9]";
    private static final String UUID = HEX + "{8}-" + HEX + "{4}-" + HEX + "{4}-" + HEX + "{4}-" + HEX + "{12}";
    private static final Pattern UUID_BASED_LOAD_BALANCED_JOB = Pattern.compile("(.*?)-(" + UUID + ")");
    final TlbEntryRepository subsetSizeRepository;
    final TlbEntryRepository testTimesRepository;
    final TlbEntryRepository failedTestsRepository;

    public TalkToGoServer(SystemEnvironment environment) {
        this(environment, createHttpAction(environment));
    }

    public TalkToGoServer(SystemEnvironment environment, HttpAction httpAction) {
        super(environment);
        this.httpAction = httpAction;
        subsetSize = null;
        jobLocator = String.format("%s/%s/%s/%s/%s", v(Go.GO_PIPELINE_NAME), v(Go.GO_PIPELINE_LABEL), v(Go.GO_STAGE_NAME), v(Go.GO_STAGE_COUNTER), v(Go.GO_JOB_NAME));
        FileUtil fileUtil = new FileUtil(environment);
        testTimesRepository = new TlbEntryRepository(fileUtil.getUniqueFile("test_times"));
        subsetSizeRepository = new TlbEntryRepository(fileUtil.getUniqueFile("subset_size"));
        failedTestsRepository = new TlbEntryRepository(fileUtil.getUniqueFile("failed_tests"));
        stageLocator = String.format("%s/%s/%s/%s", v(Go.GO_PIPELINE_NAME), v(Go.GO_PIPELINE_COUNTER), v(Go.GO_STAGE_NAME), v(Go.GO_STAGE_COUNTER));
    }

    private static HttpClient createHttpClient(SystemEnvironment environment) {
        HttpClientParams params = new HttpClientParams();
        if (environment.val(USERNAME) != null) {
            params.setAuthenticationPreemptive(true);
            HttpClient client = new HttpClient(params);
            client.getState().setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(environment.val(USERNAME), environment.val(PASSWORD)));
            return client;
        } else {
            return new HttpClient(params);
        }
    }

    private static URI createUri(SystemEnvironment environment) {
        try {
            return new URI(environment.val(Go.GO_SERVER_URL), true);
        } catch (URIException e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpAction createHttpAction(SystemEnvironment environment) {
        return new DefaultHttpAction(createHttpClient(environment), createUri(environment));
    }

    public List<String> getJobs() {
        ArrayList<String> jobNames = new ArrayList<String>();
        for (Attribute jobLink : jobLinks(String.format("%s/pipelines/%s.xml", cruiseUrl(), stageLocator))) {
            jobNames.add(rootFor(jobLink.getValue()).attributeValue(JOB_NAME));
        }
        logger.info(String.format("jobs found %s", jobNames));
        return jobNames;
    }

    @SuppressWarnings({"unchecked"})
    private List<Attribute> jobLinks(String url) {
        Element stage = rootFor(url);
        return (List<Attribute>) stage.selectNodes("//jobs/job/@href");
    }

    public Element rootFor(String url) {
        String xmlString = httpAction.get(url);
        return XmlUtil.domFor(xmlString);
    }

    private Object cruiseUrl() {
        String url = v(Go.GO_SERVER_URL);
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String v(String key) {
        return environment.val(key);
    }

    public void processedTestClassTime(String className, long time) {
        logger.info(String.format("recording run time for suite %s", className));
        testTimesRepository.appendLine(new SuiteTimeEntry(className, time).dump());
        List<String> testTimes = testTimesRepository.load();
        if (subsetSize() == testTimes.size()) {
            logger.info(String.format("Posting test run times for %s suite to the cruise server.", subsetSize()));
            postLinesToServer(SuiteTimeEntry.dump(SuiteTimeEntry.parse(testTimes)), artifactFileUrl(TEST_TIME_FILE));
            clearCachingFiles();
        }
    }

    private void postLinesToServer(String buffer, String url) {
        httpAction.put(url, buffer);
    }

    public void testClassFailure(String className, boolean hasFailed) {
        failedTestsRepository.appendLine(new SuiteResultEntry(className, hasFailed).dump());
        List<String> runTests = failedTestsRepository.load();

        if (subsetSize() == runTests.size()) {
            List<SuiteResultEntry> resultEntries = SuiteResultEntry.parse(runTests);
            postLinesToServer(SuiteResultEntry.dumpFailures(resultEntries), artifactFileUrl(FAILED_TESTS_FILE));
        }
    }

    private String artifactFileUrl(String atrifactFile) {
        return String.format("%s/files/%s/%s", cruiseUrl(), jobLocator, atrifactFile);
    }

    private int subsetSize() {
        if (subsetSize == null) {
            subsetSize = Integer.parseInt(subsetSizeRepository.loadLastLine());
        }
        return subsetSize;
    }

    public List<SuiteTimeEntry> fetchLastRunTestTimes() {
        return getLastRunTestTimes(pearJobs());
    }

    List<SuiteTimeEntry> getLastRunTestTimes(List<String> pearJobs) {
        return SuiteTimeEntry.parse(tlbArtifactPayloadLines(lastRunArtifactUrls(pearJobs, TEST_TIME_FILE)));
    }

    private List<String> lastRunArtifactUrls(List<String> jobNames, String urlSuffix) {
        String stageFeedUrl = String.format("%s/api/pipelines/%s/stages.xml", cruiseUrl(), v(Go.GO_PIPELINE_NAME));
        String stageDetailUrl = lastRunStageDetailUrl(stageFeedUrl);
        List<Attribute> jobLinks = jobLinks(stageDetailUrl);
        return tlbArtifactUrls(jobLinks, jobNames, urlSuffix);
    }

    private String tlbArtifactPayloadLines(List<String> tlbTestTimeUrls) {
        StringBuffer buffer = new StringBuffer();
        for (String tlbTestTimeUrl : tlbTestTimeUrls) {
            try {
                buffer.append(httpAction.get(tlbTestTimeUrl) + "\n");
            } catch (RuntimeException e) {
                continue; //FIXME!
            }
        }
        return buffer.toString();
    }

    private List<String> tlbArtifactUrls(List<Attribute> jobLinks, List<String> jobNames, String urlSuffix) {
        ArrayList<String> tlbAtrifactUrls = new ArrayList<String>();
        for (Attribute jobLink : jobLinks) {
            Element jobDom = rootFor(jobLink.getValue());
            String jobName = jobDom.attribute("name").getValue().trim();
            if (jobNames.contains(jobName)) {
                String atrifactBaseUrl = jobDom.selectSingleNode("//artifacts/@baseUri").getText();
                tlbAtrifactUrls.add(String.format("%s/%s", atrifactBaseUrl, urlSuffix));
            }
        }
        return tlbAtrifactUrls;
    }

    @SuppressWarnings({"unchecked"})
    private String lastRunStageDetailUrl(String stageFeedUrl) {
        Element stageFeedPage = rootFor(stageFeedUrl);
        List<Element> list = stageFeedPage.selectNodes("//a:entry");
        for (Element element : list) {
            String stageLocator = element.selectSingleNode("./a:title").getText();
            if (sameStage(stageLocator)) {
                return element.selectSingleNode("./a:link/@href").getText();
            }
        }
        return lastRunStageDetailUrl(stageFeedPage.selectSingleNode("//a:link[@rel='next']/@href").getText());
    }

    private boolean sameStage(String stageLocator) {
        Matcher matcher = STAGE_LOCATOR.matcher(stageLocator);
        if (!matcher.matches()) {
            return false;
        }
        boolean samePipeline = environment.val(Go.GO_PIPELINE_NAME).equals(matcher.group(1));
        boolean sameStage = environment.val(Go.GO_STAGE_NAME).equals(matcher.group(2));
        return samePipeline && sameStage;
    }

    public void publishSubsetSize(int size) {
        String line = String.valueOf(size) + "\n";
        subsetSizeRepository.appendLine(line);
        logger.info(String.format("Posting balanced subset size as %s to cruise server", size));
        httpAction.put(artifactFileUrl(TlbConstants.TEST_SUBSET_SIZE_FILE), line);
    }

    public void clearOtherCachingFiles() {
        for (TlbEntryRepository repository : Arrays.asList(subsetSizeRepository, testTimesRepository, failedTestsRepository)) {
            try {
                repository.cleanup();
            } catch (IOException e) {
                logger.log(Level.WARNING, "could not delete suite time cache file: " + e.getMessage(), e);
            }
        }
    }

    List<SuiteResultEntry> getLastRunFailedTests(List<String> jobNames) {
        return SuiteResultEntry.parseFailures(tlbArtifactPayloadLines(lastRunArtifactUrls(jobNames, FAILED_TESTS_FILE)));
    }

    public List<SuiteResultEntry> getLastRunFailedTests() {
        return getLastRunFailedTests(pearJobs());
    }

    protected String jobName() {
        return environment.val(Go.GO_JOB_NAME);
    }

    private String jobBaseName() {
        Matcher matcher = NUMBER_BASED_LOAD_BALANCED_JOB.matcher(jobName());
        if (matcher.matches()) {
            return matcher.group(1);
        }
        matcher = UUID_BASED_LOAD_BALANCED_JOB.matcher(jobName());
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return jobName();
    }

    private Pattern getMatcher() {
        return Pattern.compile(String.format("^%s-(" + INT + "|" + UUID + ")$", jobBaseName()));
    }

    private List<String> jobsInTheSameFamily(List<String> jobs) {
        List<String> family = new ArrayList<String>();
        Pattern pattern = getMatcher();
        for (String job : jobs) {
            if (pattern.matcher(job).matches()) {
                family.add(job);
            }
        }
        return family;
    }

    public List<String> pearJobs() {
        List<String> jobs = jobsInTheSameFamily(getJobs());
        Collections.sort(jobs);
        return jobs;
    }

    public int partitionNumber() {
        return pearJobs().indexOf(jobName()) + 1; //partition number is one based
    }

    public int totalPartitions() {
        return pearJobs().size();
    }
}
