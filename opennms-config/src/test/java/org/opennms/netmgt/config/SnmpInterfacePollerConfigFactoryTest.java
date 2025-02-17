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
package org.opennms.netmgt.config;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opennms.core.test.ConfigurationTestUtils;
import org.opennms.netmgt.config.snmpinterfacepoller.*;
import org.opennms.netmgt.config.snmpinterfacepoller.Package;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SnmpInterfacePollerConfigFactoryTest {
    private SnmpInterfacePollerConfigFactory m_factory;

    @Before
    public void testSnmpInterfacePollerConfigFactory() throws IOException {
        InputStream is = ConfigurationTestUtils.getInputStreamForResource(this, "/etc/snmp-interface-poller-configuration.xml");
        m_factory = new SnmpInterfacePollerConfigFactory(-1L, is);
    }

    @Test
    public void testThreads() {
        Assert.assertEquals(30, m_factory.getThreads());
    }

    @Test
    public void testService() {
        Assert.assertEquals("SNMP", m_factory.getService());
    }

    @Test
    public void testUseCriteriaFilter() {
        Assert.assertTrue(m_factory.useCriteriaFilters());
    }

    @Test
    public void testGlobalInterval() {
        Assert.assertEquals(300000, m_factory.getInterval());
    }

    @Test
    public void testUpValues() {
        Assert.assertEquals("1,5", m_factory.getUpValues());
    }

    @Test
    public void testDownValues() {
        Assert.assertEquals("2,3,4,6,7", m_factory.getDownValues());
    }

    @Test
    public void testCriticalServices() {
        String[] critsvcs = new String[]{"ICMP", "SNMP"};
        Assert.assertArrayEquals(critsvcs, m_factory.getCriticalServiceIds());
    }

    @Test
    public void testExample1() {
        Package p = new Package();
        Filter f = new Filter();
        f.setContent("IPADDR != '0.0.0.0'");
        p.setName("example1");
        p.setFilter(f);
        IncludeRange ir1 = new IncludeRange();
        ir1.setBegin("1.1.1.1");
        ir1.setEnd("1.1.1.2");
        p.addIncludeRange(ir1);
        IncludeRange ir2 = new IncludeRange();
        ir2.setBegin("::1");
        ir2.setEnd("::1");
        p.addIncludeRange(ir2);
        ExcludeRange er = new ExcludeRange();
        er.setBegin("2.2.2.2");
        er.setEnd("2.2.2.3");
        p.addExcludeRange(er);
        List<String> includeUrls = new ArrayList<String>(1);
        includeUrls.add("file:///dev/null");
        p.setIncludeUrls(includeUrls);
        p.addSpecific("127.0.0.1");
        Interface i1 = new Interface();
        i1.setName("Ethernet");
        i1.setCriteria("snmpiftype = 6");
        i1.setInterval(300000L);
        i1.setUserDefined(false);
        i1.setStatus("on");
        i1.setTimeout(5);
        i1.setRetry(6);
        i1.setPort(616);
        i1.setMaxVarsPerPdu(11);
        i1.setUpValues("1");
        i1.setDownValues("2,3");
        p.addInterface(i1);
        Interface i2 = new Interface();
        i2.setCriteria("snmpiftype = 7");
        i2.setName("Sevenet");
        i2.setInterval(300001L);
        i2.setUserDefined(false);
        i2.setStatus("on");
        i2.setMaxVarsPerPdu(10);
        p.addInterface(i2);
        Assert.assertTrue(p.equals(m_factory.getPackage("example1")));
    }

    @Test
    public void testExample2() {
        Package p = new Package();
        p.setName("example2");
        Filter f = new Filter();
        f.setContent("IPADDR = '2.2.2.2'");
        p.setFilter(f);
        Interface i = new Interface();
        i.setName("deuce");
        i.setInterval(222222L);
        p.addInterface(i);
        Assert.assertTrue(p.equals(m_factory.getPackage("example2")));
    }

    @Test
    public void resolveEffectiveUpValues() {
        Assert.assertEquals("1", m_factory.getUpValues("example1", "Ethernet"));
        Assert.assertEquals("1,5", m_factory.getUpValues("example2", "deuce"));
    }

    @Test
    public void resolveEffectiveDownValues() {
        Assert.assertEquals("2,3", m_factory.getDownValues("example1", "Ethernet"));
        Assert.assertEquals("2,3,4,6,7", m_factory.getDownValues("example2", "deuce"));
    }

}
