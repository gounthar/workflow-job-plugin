package org.jenkinsci.plugins.workflow.job;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import hudson.cli.CLICommandInvoker;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.security.WhoAmI;
import hudson.triggers.SCMTrigger;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.plugins.git.GitSampleRepoRule;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RunLoadCounter;

public class WorkflowJobTest {
    private static final Logger LOGGER = Logger.getLogger(WorkflowJobTest.class.getName());

    @ClassRule public static BuildWatcher watcher = new BuildWatcher();

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @Issue("JENKINS-40255")
    @Test public void getSCM() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  checkout(new hudson.scm.NullSCM())\n" +
            "}", false /* for hudson.scm.NullSCM */));
        assertTrue("No runs has been performed and there should be no SCMs", p.getSCMs().isEmpty());

        j.buildAndAssertSuccess(p);

        assertEquals("Expecting one SCM", 1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("error 'Fail!'", true));

        j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));

        assertEquals("Expecting one SCM even though last run failed",1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("echo 'Pass!'", true));

        j.buildAndAssertSuccess(p);

        assertEquals("Expecting zero SCMs",0, p.getSCMs().size());
    }

    @Issue("JENKINS-34716")
    @Test public void polling() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        j.assertLogContains("first version", j.buildAndAssertSuccess(p));
        sampleRepo.write("Jenkinsfile", "echo 'second version'");
        sampleRepo.git("commit", "-a", "-m", "init");
        j.jenkins.setQuietPeriod(0);
        j.createWebClient().getPage(new WebRequest(j.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getLastBuild();
        assertEquals(2, b2.getNumber());
        j.assertLogContains("second version", b2);
    }

    @Issue("JENKINS-38669")
    @Test public void nonEmptySCMListForGitSCMJobBeforeBuild() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        CpsScmFlowDefinition def = new CpsScmFlowDefinition(new GitSCM("I don't care"), "Jenkinsfile");
        assertEquals("Expecting one SCM for definition", 1, def.getSCMs().size());
        p.setDefinition(def);
        assertEquals("Expecting one SCM", 1, p.getSCMs().size());
    }

    @Issue("JENKINS-38669")
    @Test public void neverBuiltSCMBasedJobMustBeTriggerableByHook() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        j.jenkins.setQuietPeriod(0);
        j.createWebClient().getPage(new WebRequest(j.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        j.waitUntilNoActivity();
        WorkflowRun b1 = p.getLastBuild();
        assertEquals(1, b1.getNumber());
        j.assertLogContains("first version", b1);
    }

    @Test
    public void addAction() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        WhoAmI a = new WhoAmI();
        p.addAction(a);
        assertNotNull(p.getAction(WhoAmI.class));
    }

    @Issue("JENKINS-27299")
    @Test public void disabled() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        JenkinsRule.WebClient wc = j.createWebClient();
        assertDisabled(p, false, wc);

        // Disable the project
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        HtmlCheckBoxInput checkbox = form.getInputByName("enable");
        assertTrue(checkbox.isChecked());
        checkbox.setChecked(false);
        j.submit(form);
        assertDisabled(p, true, wc);

        // Re-enable the project
        form = wc.getPage(p, "configure").getFormByName("config");
        checkbox = form.getInputByName("enable");
        assertFalse(checkbox.isChecked());
        checkbox.setChecked(true);
        j.submit(form);
        assertDisabled(p, false, wc);

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "disable"), HttpMethod.POST));
        assertDisabled(p, true, wc);
        assertNull(p.scheduleBuild2(0));
        assertThat(new CLICommandInvoker(j, "enable-job").invokeWithArgs("p"), CLICommandInvoker.Matcher.succeededSilently());
        assertDisabled(p, false, wc);
    }

    private void assertDisabled(WorkflowJob p, boolean disabled, JenkinsRule.WebClient wc) throws Exception {
        assertThat(p.isDisabled(), is(disabled));
        assertThat(p.isBuildable(), is(!disabled));
        assertThat(wc.getJSON(p.getUrl() + "api/json?tree=disabled,buildable").getJSONObject(),
            is(new JSONObject().accumulate("_class", WorkflowJob.class.getName()).accumulate("disabled", disabled).accumulate("buildable", !disabled)));
    }

    @Test
    public void newBuildsShouldNotLoadOld() throws Throwable {
        var p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        for (int i = 0; i < 10; i++) {
            j.buildAndAssertSuccess(p);
        }
        RunLoadCounter.assertMaxLoads(p, /* just lastBuild */ 1, () -> {
            for (int i = 0; i < 5; i++) {
                j.buildAndAssertSuccess(p);
            }
            return null;
        });
    }

    @Issue("JENKINS-73824")
    @Test
    public void deletionShouldWaitForBuildsToComplete() throws Throwable {
        var p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                """
                try {
                  echo 'about to sleep'
                  sleep 999
                } catch(e) {
                  echo 'aborting soon'
                  sleep 3
                }
                """, true));
        var b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("about to sleep", b);
        // The build isn't done and catches the interruption, so ItemDeletion.cancelBuildsInProgress should have to wait at least 3 seconds for it to complete.
        LOGGER.info(() -> "Deleting " + p);
        p.delete();
        LOGGER.info(() -> "Deleted " + p);
        // Make sure that the job really has been deleted.
        assertThat(j.jenkins.getItemByFullName(p.getFullName()), nullValue());
        // ItemDeletion.cancelBuildsInProgress should guarantee that the queue is empty at this point.
        var executables = Stream.of(j.jenkins.getComputers())
                .flatMap(c -> c.getAllExecutors().stream())
                .map(Executor::getCurrentExecutable)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        assertThat(executables, empty());
    }

}
