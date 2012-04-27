/*
 * (C) Copyright 2006-2012 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thomas Roger <troger@nuxeo.com>
 */

package org.nuxeo.ecm.multi.tenant;

import static org.nuxeo.ecm.core.api.security.SecurityConstants.EVERYONE;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.EVERYTHING;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANTS_DIRECTORY;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_CONFIG_FACET;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_ID_PROPERTY;
import static org.nuxeo.ecm.multi.tenant.MultiTenantHelper.computeTenantAdministratorsGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;

/**
 * @author <a href="mailto:troger@nuxeo.com">Thomas Roger</a>
 * @since 5.6
 */
public class MultiTenantServiceImpl implements MultiTenantService {

    private static final Log log = LogFactory.getLog(MultiTenantServiceImpl.class);

    public static final String TENANT_ACL_NAME = "tenantACP";

    private Boolean isTenantIsolationEnabled;

    @Override
    public boolean isTenantIsolationEnabled(CoreSession session)
            throws ClientException {
        if (isTenantIsolationEnabled == null) {
            final List<DocumentModel> tenants = new ArrayList<DocumentModel>();
            new UnrestrictedSessionRunner(session) {
                @Override
                public void run() throws ClientException {
                    String query = "SELECT * FROM Document WHERE ecm:mixinType = 'TenantConfig'";
                    tenants.addAll(session.query(query));
                }
            }.runUnrestricted();
            isTenantIsolationEnabled = !tenants.isEmpty();
        }
        return isTenantIsolationEnabled;
    }

    @Override
    public void enableTenantIsolation(CoreSession session)
            throws ClientException {
        if (!isTenantIsolationEnabled(session)) {
            new UnrestrictedSessionRunner(session) {
                @Override
                public void run() throws ClientException {
                    String query = "SELECT * FROM Document WHERE ecm:primaryType = 'Domain'";
                    List<DocumentModel> docs = session.query(query);
                    for (DocumentModel doc : docs) {
                        enableTenantIsolationFor(session, doc);
                    }
                    session.save();
                }
            }.runUnrestricted();
            isTenantIsolationEnabled = true;
        }
    }

    @Override
    public void disableTenantIsolation(CoreSession session)
            throws ClientException {
        if (isTenantIsolationEnabled(session)) {
            new UnrestrictedSessionRunner(session) {
                @Override
                public void run() throws ClientException {
                    String query = "SELECT * FROM Document WHERE ecm:mixinType = 'TenantConfig'";
                    List<DocumentModel> docs = session.query(query);
                    for (DocumentModel doc : docs) {
                        disableTenantIsolationFor(session, doc);
                    }
                    session.save();
                }
            }.runUnrestricted();
            isTenantIsolationEnabled = false;
        }
    }

    @Override
    public void enableTenantIsolationFor(CoreSession session, DocumentModel doc)
            throws ClientException {
        if (!doc.hasFacet(TENANT_CONFIG_FACET)) {
            doc.addFacet(TENANT_CONFIG_FACET);
        }

        DocumentModel d = registerTenant(doc);
        String tenantId = (String) d.getPropertyValue("tenant:id");
        doc.setPropertyValue(TENANT_ID_PROPERTY, tenantId);

        setTenantACL(tenantId, doc);
        session.saveDocument(doc);
    }

    private DocumentModel registerTenant(DocumentModel doc)
            throws ClientException {
        DirectoryService directoryService = Framework.getLocalService(DirectoryService.class);
        Session session = null;
        try {
            session = directoryService.open(TENANTS_DIRECTORY);
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("id", doc.getName());
            m.put("label", doc.getTitle());
            m.put("docId", doc.getId());
            return session.createEntry(m);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private void setTenantACL(String tenantId, DocumentModel doc)
            throws ClientException {
        ACP acp = doc.getACP();
        ACL acl = acp.getOrCreateACL();
        UserManager userManager = Framework.getLocalService(UserManager.class);
        for (String adminGroup : userManager.getAdministratorsGroups()) {
            acl.add(new ACE(adminGroup, EVERYTHING, true));
        }

        String tenantAdministratorsGroup = computeTenantAdministratorsGroup(tenantId);
        acl.add(new ACE(tenantAdministratorsGroup,
                SecurityConstants.EVERYTHING, true));
        acl.add(new ACE(EVERYONE, SecurityConstants.EVERYTHING, false));
        doc.setACP(acp, true);
    }

    @Override
    public void disableTenantIsolationFor(CoreSession session, DocumentModel doc)
            throws ClientException {
        if (session.exists(doc.getRef())) {
            if (doc.hasFacet(TENANT_CONFIG_FACET)) {
                doc.removeFacet(TENANT_CONFIG_FACET);
            }
            // removeTenantACL(doc);
            session.saveDocument(doc);
        }
        unregisterTenant(doc);
    }

    private void removeTenantACL(DocumentModel doc) throws ClientException {
        // ACP acp = doc.getACP();
        // acp.removeACL(TENANT_ACL_NAME);
        // doc.setACP(acp, true);

        ACP acp = doc.getACP();
        ACL acl = acp.getOrCreateACL();
        List<ACE> aces = new ArrayList<ACE>();
        UserManager userManager = Framework.getLocalService(UserManager.class);
        for (String adminGroup : userManager.getAdministratorsGroups()) {
            aces.add(new ACE(adminGroup, EVERYTHING, true));
        }

        String tenantId = doc.getName();
        String tenantAdministratorsGroup = computeTenantAdministratorsGroup(tenantId);
        aces.add(new ACE(tenantAdministratorsGroup,
                SecurityConstants.EVERYTHING, true));
        aces.add(new ACE(EVERYONE, SecurityConstants.EVERYTHING, false));

        acl.removeAll(aces);
        doc.setACP(acp, true);
    }

    private void unregisterTenant(DocumentModel doc) throws ClientException {
        DirectoryService directoryService = Framework.getLocalService(DirectoryService.class);
        Session session = null;
        try {
            session = directoryService.open(TENANTS_DIRECTORY);
            session.deleteEntry(doc.getName());
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    @Override
    public List<DocumentModel> getTenants() throws ClientException {
        DirectoryService directoryService = Framework.getLocalService(DirectoryService.class);
        Session session = null;
        try {
            session = directoryService.open(TENANTS_DIRECTORY);
            return session.getEntries();
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

}
