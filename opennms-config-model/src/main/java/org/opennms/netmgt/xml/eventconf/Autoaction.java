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
package org.opennms.netmgt.xml.eventconf;

import java.io.Serializable;
import java.util.Objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.opennms.core.xml.NullStringAdapter;
import org.opennms.core.xml.ValidateUsing;
import org.opennms.netmgt.config.utils.ConfigUtils;

/**
 * The automatic action to occur when this event occurs with
 *  state controlling if action takes place
 */
@XmlRootElement(name="autoaction")
@XmlAccessorType(XmlAccessType.NONE)
@ValidateUsing("eventconf.xsd")
public class Autoaction implements Serializable {
    private static final long serialVersionUID = 2L;

    @XmlValue
    @XmlJavaTypeAdapter(NullStringAdapter.class)
    private String m_content;

    @XmlAttribute(name="state", required=false)
    private StateType m_state;

    public String getContent() {
        return m_content;
    }

    public void setContent(final String content) {
        m_content = ConfigUtils.normalizeAndInternString(content);
    }

    public StateType getState() {
        return m_state == null? StateType.ON : m_state; // XSD default is on
    }

    public void setState(final StateType state) {
        m_state = state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(m_content, m_state);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Autoaction) {
            final Autoaction that = (Autoaction) obj;
            return Objects.equals(this.m_content, that.m_content) &&
                    Objects.equals(this.m_state, that.m_state);
        }
        return false;
    }

}
