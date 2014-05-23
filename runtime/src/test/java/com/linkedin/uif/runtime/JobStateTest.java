package com.linkedin.uif.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.io.Closer;

import com.linkedin.uif.configuration.ConfigurationKeys;
import com.linkedin.uif.configuration.WorkUnitState;

/**
 * Unit test for {@link JobState}.
 *
 * @author ynli
 */
@Test(groups = {"com.linkedin.uif.runtime"})
public class JobStateTest {

    private JobState jobState;
    private long startTime;

    @BeforeClass
    public void setUp() {
        this.jobState = new JobState("TestJob", "TestJob-1");
    }

    @Test
    public void testSetAndGet() {
        this.jobState.setId(this.jobState.getJobId());
        this.startTime = System.currentTimeMillis();
        this.jobState.setStartTime(this.startTime);
        this.jobState.setEndTime(this.startTime + 1000);
        this.jobState.setDuration(1000);
        this.jobState.setState(JobState.RunningState.COMMITTED);
        this.jobState.setTasks(3);
        this.jobState.setProp("foo", "bar");
        for (int i = 0; i < 3; i++) {
            WorkUnitState workUnitState = new WorkUnitState();
            workUnitState.setProp(ConfigurationKeys.JOB_ID_KEY, "TestJob-1");
            workUnitState.setProp(ConfigurationKeys.TASK_ID_KEY, "TestTask-1");
            TaskState taskState = new TaskState(workUnitState);
            taskState.setId(taskState.getTaskId());
            taskState.setStartTime(this.startTime);
            taskState.setEndTime(this.startTime + 1000);
            taskState.setTaskDuration(1000);
            taskState.setWorkingState(WorkUnitState.WorkingState.COMMITTED);
            taskState.setProp("foo", "bar");
            this.jobState.addTaskState(taskState);
        }

        doAsserts();
    }

    @Test(dependsOnMethods = {"testSetAndGet"})
    public void testSerDe() throws IOException {
        Closer closer = Closer.create();
        try {
            ByteArrayOutputStream baos = closer.register(new ByteArrayOutputStream());
            DataOutputStream dos = closer.register(new DataOutputStream(baos));
            this.jobState.write(dos);

            ByteArrayInputStream bais = closer.register((new ByteArrayInputStream(baos.toByteArray())));
            DataInputStream dis = closer.register((new DataInputStream(bais)));
            JobState newJobState = new JobState();
            newJobState.readFields(dis);

            doAsserts();
        } finally {
            closer.close();
        }
    }

    private void doAsserts() {
        Assert.assertEquals(this.jobState.getJobName(), "TestJob");
        Assert.assertEquals(this.jobState.getJobId(), "TestJob-1");
        Assert.assertEquals(this.jobState.getId(), "TestJob-1");
        Assert.assertEquals(this.jobState.getStartTime(), this.startTime);
        Assert.assertEquals(this.jobState.getEndTime(), this.startTime + 1000);
        Assert.assertEquals(this.jobState.getDuration(), 1000);
        Assert.assertEquals(this.jobState.getState(), JobState.RunningState.COMMITTED);
        Assert.assertEquals(this.jobState.getTasks(), 3);
        Assert.assertEquals(this.jobState.getCompletedTasks(), 3);
        Assert.assertEquals(this.jobState.getProp("foo"), "bar");

        for (int i = 0; i < this.jobState.getCompletedTasks(); i++) {
            TaskState taskState = this.jobState.getTaskStates().get(i);
            Assert.assertEquals(taskState.getJobId(), "TestJob-1");
            Assert.assertEquals(taskState.getTaskId(), "TestTask-1");
            Assert.assertEquals(taskState.getId(), "TestTask-1");
            Assert.assertEquals(taskState.getStartTime(), this.startTime);
            Assert.assertEquals(taskState.getEndTime(), this.startTime + 1000);
            Assert.assertEquals(taskState.getTaskDuration(), 1000);
            Assert.assertEquals(taskState.getWorkingState(), WorkUnitState.WorkingState.COMMITTED);
            Assert.assertEquals(taskState.getProp("foo"), "bar");
        }
    }
}