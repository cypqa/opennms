/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.vacuumd;

import java.beans.PropertyVetoException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.sql.DataSource;

import org.opennms.core.db.DataSourceFactory;
import org.opennms.core.logging.Logging;
import org.opennms.netmgt.config.VacuumdConfigFactory;
import org.opennms.netmgt.config.vacuumd.Action;
import org.opennms.netmgt.config.vacuumd.Automation;
import org.opennms.netmgt.config.vacuumd.Statement;
import org.opennms.netmgt.config.vacuumd.Trigger;
import org.opennms.netmgt.daemon.AbstractServiceDaemon;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.events.api.model.IParm;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.scheduler.LegacyScheduler;
import org.opennms.netmgt.scheduler.Schedule;
import org.opennms.netmgt.scheduler.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a daemon whose job it is to run periodic updates against the
 * database for database maintenance work.
 *
 * @author <a href=mailto:brozow@opennms.org>Mathew Brozowski</a>
 * @author <a href=mailto:david@opennms.org>David Hustace</a>
 * @author <a href=mailto:dj@opennms.org>DJ Gregor</a>
 */
public class Vacuumd extends AbstractServiceDaemon implements Runnable, EventListener {
    
    private static final Logger LOG = LoggerFactory.getLogger(Vacuumd.class);
    
    private static volatile Vacuumd m_singleton;

    private volatile Thread m_thread;

    private volatile long m_startTime;

    private volatile boolean m_stopped = false;

    private volatile LegacyScheduler m_scheduler;

    private volatile EventIpcManager m_eventMgr;

    /**
     * <p>getSingleton</p>
     *
     * @return a {@link org.opennms.netmgt.vacuumd.Vacuumd} object.
     */
    public static synchronized Vacuumd getSingleton() {
        if (m_singleton == null) {
            m_singleton = new Vacuumd();
        }
        return m_singleton;
    }

    /**
     */
    public static synchronized void destroySingleton() {
        if (m_singleton != null) {
            m_singleton.stop();
            m_singleton = null;
        }
    }

    /**
     * <p>Constructor for Vacuumd.</p>
     */
    public Vacuumd() {
        super("vacuumd");
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.opennms.netmgt.vacuumd.jmx.VacuumdMBean#init()
     */
    /** {@inheritDoc} */
    @Override
    protected void onInit() {
        try {
            LOG.info("Loading the configuration file.");
            VacuumdConfigFactory.init();
            getEventManager().addEventListener(this, EventConstants.RELOAD_VACUUMD_CONFIG_UEI);
            getEventManager().addEventListener(this, EventConstants.RELOAD_DAEMON_CONFIG_UEI);

            initializeDataSources();
        } catch (Throwable ex) {
            LOG.error("Failed to load outage configuration", ex);
            throw new UndeclaredThrowableException(ex);
        }

        LOG.info("Vacuumd initialization complete");

        createScheduler();
        scheduleAutomations();
    }

    private void initializeDataSources() throws IOException, ClassNotFoundException, PropertyVetoException, SQLException {
        for (Trigger trigger : getVacuumdConfig().getTriggers()) {
            DataSourceFactory.init(trigger.getDataSource());
        }

        for (Action action : getVacuumdConfig().getActions()) {
            DataSourceFactory.init(action.getDataSource());
        }
    }

    private void createAndStartThread() {
        m_thread = new Thread(Logging.preserve(this), "Vacuumd-Thread");
        m_thread.start();
    }

    /** {@inheritDoc} */
    @Override
    protected void onStart() {
        m_startTime = System.currentTimeMillis();
        createAndStartThread();
        m_scheduler.start();
    }

    /** {@inheritDoc} */
    @Override
    protected void onStop() {
        m_stopped = true;
        if (m_scheduler != null && m_scheduler.getStatus() == RUNNING) {
            m_scheduler.stop();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        m_scheduler.pause();
        m_stopped = true;
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        createAndStartThread();
        m_scheduler.resume();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    /**
     * <p>run</p>
     */
    @Override
    public void run() {
        LOG.info("Vacuumd scheduling started");

        long now = System.currentTimeMillis();
        long period = getVacuumdConfig().getPeriod();

        LOG.info("Vacuumd sleeping until time to execute statements period = {}", period);

        long waitTime = 500L;

        while (!m_stopped) {
            try {
                now = waitPeriod(now, period, waitTime);

                LOG.info("Vacuumd beginning to execute statements");
                executeStatements();

                m_startTime = System.currentTimeMillis();

            } catch (Throwable e) {
                LOG.error("Unexpected exception", e);
            }
        }
    }

    /**
     * <p>executeStatements</p>
     */
    protected void executeStatements() {
        if (!m_stopped) {
            List<Statement> statements = getVacuumdConfig().getStatements();
            for (Statement statement : statements) {
				runUpdate(statement.getContent(), statement.getTransactional());
			}
        }
    }

    /**
     * @param now
     * @param period
     * @param waitTime
     * @return
     */
    private long waitPeriod(long now, long period, long waitTime) {
        int count = 0;
        while (!m_stopped && ((now - m_startTime) < period)) {
            try {
                if (count % 100 == 0) {
                    LOG.debug("Vacuumd: {}ms remaining to execution.", (period - now + m_startTime));
                }
                Thread.sleep(waitTime);
                now = System.currentTimeMillis();
                count++;
            } catch (InterruptedException e) {
                // FIXME: what do I do here?
            }
        }
        return now;
    }

    private void runUpdate(String sql, boolean transactional) {
        LOG.info("Vacuumd executing statement: {}", sql);
        // update the database
        Connection dbConn = null;
        
        //initially set doCommit to avoid doing a commit in the finally
        //if an exception is thrown.        
        boolean commitRequired = false;
        boolean autoCommitFlag = !transactional;
        try {
            dbConn = getDataSourceFactory().getConnection();
            dbConn.setAutoCommit(autoCommitFlag);

            PreparedStatement stmt = dbConn.prepareStatement(sql);
            int count = stmt.executeUpdate();
            stmt.close();

            LOG.debug("Vacuumd: Ran update {}: this affected {} rows", sql, count);

            commitRequired = transactional;
        } catch (SQLException ex) {
            LOG.error("Vacuumd:  Database error execuating statement {}", sql, ex);
        } finally {
            if (dbConn != null) {
                try {
                    if (commitRequired) {
                        dbConn.commit();
                    } else if (transactional) {
                        dbConn.rollback();
                    }
                } catch (SQLException ex) {
                } finally {
                    if (dbConn != null) {
                        try {
                            dbConn.close();
                        } catch (Throwable e) {
                        }
                    }
                }
            }
        }
    }

    private void createScheduler() {
        try {
            LOG.debug("init: Creating Vacuumd scheduler");
            m_scheduler = new LegacyScheduler("Vacuumd", 2);
        } catch (RuntimeException e) {
            LOG.error("init: Failed to create Vacuumd scheduler", e);
            throw e;
        }
    }

    /**
     * <p>getScheduler</p>
     *
     * @return a {@link org.opennms.netmgt.scheduler.Scheduler} object.
     */
    public Scheduler getScheduler() {
        return m_scheduler;
    }

    private void scheduleAutomations() {
        for (Automation auto : getVacuumdConfig().getAutomations()) {
            try {
                scheduleAutomation(auto);
            } catch (Exception e) {
                LOG.warn("Could not schedule automation {}", auto.getActionName(), e);
            }
        }
    }

    private void scheduleAutomation(Automation auto) {
        if (auto.getActive()) {
            AutomationProcessor ap = new AutomationProcessor(auto);
            Schedule s = new Schedule(ap, new AutomationInterval(auto.getInterval()), m_scheduler);
            ap.setSchedule(s);
            s.schedule();
        }
    }

    /**
     * <p>getEventManager</p>
     *
     * @return a {@link org.opennms.netmgt.events.api.EventIpcManager} object.
     */
    public EventIpcManager getEventManager() {
        return m_eventMgr;
    }

    /**
     * <p>setEventManager</p>
     *
     * @param eventMgr a {@link org.opennms.netmgt.events.api.EventIpcManager} object.
     */
    public void setEventManager(EventIpcManager eventMgr) {
        m_eventMgr = eventMgr;
    }

    /** {@inheritDoc} */
    @Override
    public void onEvent(IEvent event) {

        if (isReloadConfigEvent(event)) {
            handleReloadConifgEvent();
        }
    }

    private void handleReloadConifgEvent() {
        LOG.info("onEvent: reloading configuration...");
        
        EventBuilder ebldr = null;
        
        try {
            LOG.debug("onEvent: Number of elements in schedule:{}; calling stop on scheduler...", m_scheduler.getScheduled());
            stop();
            ExecutorService runner = m_scheduler.getRunner();
            while (!runner.isShutdown() || m_scheduler.getStatus() != STOPPED) {
                LOG.debug("onEvent: waiting for scheduler to stop. Current status of scheduler: {}; Current status of runner: {}", m_scheduler.getStatus(), (runner.isTerminated() ? "TERMINATED" : (runner.isShutdown() ? "SHUTDOWN" : "RUNNING")));
                Thread.sleep(500);
            }
            LOG.debug("onEvent: Current status of scheduler: {}; Current status of runner: {}", m_scheduler.getStatus(), (runner.isTerminated() ? "TERMINATED" : (runner.isShutdown() ? "SHUTDOWN" : "RUNNING")));
            LOG.debug("onEvent: Number of elements in schedule: {}", m_scheduler.getScheduled());
            LOG.debug("onEvent: reloading vacuumd configuration.");

            VacuumdConfigFactory.reload();
            LOG.debug("onEvent: creating new schedule and rescheduling automations.");

            init();
            LOG.debug("onEvent: restarting vacuumd and scheduler.");

            start();
            LOG.debug("onEvent: Number of elements in schedule: {}", m_scheduler.getScheduled());
            
            ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, getName());
            ebldr.addParam(EventConstants.PARM_DAEMON_NAME, "Vacuumd");
        } catch (IOException e) {
            LOG.error("onEvent: IO problem reading vacuumd configuration", e);
            ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, getName());
            ebldr.addParam(EventConstants.PARM_DAEMON_NAME, "Vacuumd");
            ebldr.addParam(EventConstants.PARM_REASON, e.getLocalizedMessage().substring(0, 128));
        } catch (InterruptedException e) {
            LOG.error("onEvent: Problem interrupting current Vacuumd Thread", e);
            ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, getName());
            ebldr.addParam(EventConstants.PARM_DAEMON_NAME, "Vacuumd");
            ebldr.addParam(EventConstants.PARM_REASON, e.getLocalizedMessage().substring(0, 128));
		}
        
        LOG.info("onEvent: completed configuration reload.");
        
        if (ebldr != null) {
            m_eventMgr.sendNow(ebldr.getEvent());
        }
    }

    private boolean isReloadConfigEvent(IEvent event) {
        boolean isTarget = false;
        
        if (EventConstants.RELOAD_DAEMON_CONFIG_UEI.equals(event.getUei())) {
            List<IParm> parmCollection = event.getParmCollection();
            
            for (IParm parm : parmCollection) {
                if (EventConstants.PARM_DAEMON_NAME.equals(parm.getParmName()) && "Vacuumd".equalsIgnoreCase(parm.getValue().getContent())) {
                    isTarget = true;
                    break;
                }
            }
        
        //Depreciating this one...
        } else if (EventConstants.RELOAD_VACUUMD_CONFIG_UEI.equals(event.getUei())) {
            isTarget = true;
        }
        
        return isTarget;
    }

    /**
     * Returns the number of automations that have been executed so far.
     *
     * @return the number of automations that have been executed
     */
    public long getNumAutomations() {
        if (m_scheduler != null) {
            return m_scheduler.getNumTasksExecuted();
        } else {
            return 0L;
        }
    }

    private VacuumdConfigFactory getVacuumdConfig() {
        return VacuumdConfigFactory.getInstance();
    }
    
    private DataSource getDataSourceFactory() {
        return DataSourceFactory.getInstance();
    }
}
