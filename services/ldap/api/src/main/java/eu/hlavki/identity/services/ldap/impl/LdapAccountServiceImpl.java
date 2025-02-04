package eu.hlavki.identity.services.ldap.impl;

import com.unboundid.ldap.sdk.*;
import static com.unboundid.ldap.sdk.ModificationType.*;
import static com.unboundid.ldap.sdk.SearchScope.*;
import eu.hlavki.identity.services.ldap.LdapAccountService;
import eu.hlavki.identity.services.ldap.LdapSystemException;
import eu.hlavki.identity.services.ldap.config.Configurable;
import eu.hlavki.identity.services.ldap.config.Configuration;
import eu.hlavki.identity.services.ldap.model.LdapAccount;
import eu.hlavki.identity.services.ldap.model.LdapGroup;
import java.util.*;
import static java.util.Optional.empty;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LdapAccountServiceImpl implements LdapAccountService, Configurable {

    private static final Logger log = LoggerFactory.getLogger(LdapAccountServiceImpl.class);

    public static final String GROUP_NAME_ATTR = "cn";
    private static final String GROUP_DESC_ATTR = "description";

    private final LDAPConnectionPool ldapPool;
    private final Configuration config;


    public LdapAccountServiceImpl(Configuration config, LDAPConnectionPool ldapPool) {
        this.config = config;
        this.ldapPool = ldapPool;
    }


    @Override
    public void reconfigure() {
        log.info("Configuring UserLdapService...");
        try (LDAPConnection conn = ldapPool.getConnection()) {
            creatOrgUnits(conn);
        } catch (LDAPException e) {
            log.error("Can't create org units!", e);
        }
    }


    @Override
    public boolean accountExists(String subject) throws LdapSystemException {
        return getAccountDN(subject) != null;
    }


    @Override
    public String getAccountDN(String subject) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDn = config.getLdapUserBaseDN();
            SearchResultEntry entry = conn.searchForEntry(baseDn, ONE, "(employeeNumber=" + subject + ")", "uid");
            return entry != null ? entry.getDN() : null;
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public String getAccountDN(LdapAccount account) {
        return config.getUserAttr() + "=" + account.getAccount() + "," + config.getLdapUserBaseDN();
    }


    @Override
    public Optional<LdapAccount> getAccountBySubject(String subject) throws LdapSystemException {
        Optional<LdapAccount> result = empty();
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDn = config.getLdapUserBaseDN();
            SearchResultEntry entry = conn.searchForEntry(baseDn, ONE, "(employeeNumber=" + subject + ")");
            if (entry != null) {
                result = Optional.of(accountFromEntry(entry));
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
        return result;
    }


    @Override
    public Optional<LdapAccount> getAccountByEmail(String email) throws LdapSystemException {
        Optional<LdapAccount> result = empty();
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDn = config.getLdapUserBaseDN();
            SearchResultEntry entry = conn.searchForEntry(baseDn, ONE, "(mail=" + email + ")");
            if (entry != null) {
                result = Optional.of(accountFromEntry(entry));
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
        return result;
    }


    @Override
    public List<LdapAccount> searchAccounts(LdapAccount.Role role) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDn = config.getLdapUserBaseDN();
            String roleStr = String.valueOf(role);
            SearchResult searchResult = conn.search(baseDn, ONE, "(&(objectClass=inetOrgPerson)(employeeType=" + roleStr + "))");
            return searchResult.getSearchEntries().stream().map(entry -> accountFromEntry(entry)).collect(Collectors.toList());
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public List<LdapAccount> getAllAccounts() throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDn = config.getLdapUserBaseDN();
            SearchResult searchResult = conn.search(baseDn, ONE, "(objectClass=inetOrgPerson)");
            return searchResult.getSearchEntries().stream().map(entry -> accountFromEntry(entry)).collect(Collectors.toList());
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void createAccount(LdapAccount account) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String entryDN = getAccountDN(account);
            log.info("Creating user with DN {}", entryDN);
            Entry entry = new Entry(entryDN);
            entry.addAttribute("objectClass", "inetOrgPerson");
            for (String email : account.getEmails()) {
                entry.addAttribute("mail", email);
            }
            entry.addAttribute("displayName", account.getName());
            entry.addAttribute("givenName", account.getGivenName());
            entry.addAttribute("sn", account.getFamilyName());
            entry.addAttribute("cn", account.getUsername());
            entry.addAttribute("employeeNumber", account.getSubject());
            entry.addAttribute("userPassword", account.getSHAPassword());
            entry.addAttribute("employeeType", account.getRole().toString());
            conn.add(entry);
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void updateAccount(LdapAccount account) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            List<Modification> mods = new ArrayList<>();
            if (account.getPassword() != null) {
                mods.add(new Modification(REPLACE, "userPassword", account.getSHAPassword()));
            }
            mods.add(new Modification(REPLACE, "displayName", account.getName()));
            mods.add(new Modification(REPLACE, "givenName", account.getGivenName()));
            mods.add(new Modification(REPLACE, "sn", account.getFamilyName()));
            mods.add(new Modification(REPLACE, "mail", account.getEmails().toArray(new String[0])));
            conn.modify(new ModifyRequest(getAccountDN(account.getSubject()), mods));
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public LdapGroup getGroup(String groupName) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            return getGroup(groupName, conn);
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public Set<String> getAllGroupNames() throws LdapSystemException {
        Set<String> result = new HashSet<>();
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDN = config.getLdapGroupsBaseDN();
            log.info("Group base DN: " + baseDN);
            String filter = "(objectClass=" + config.getLdapGroupsObjectClass() + ")";
            SearchResult searchResult = conn.search(baseDN, SUB, filter, GROUP_NAME_ATTR);
            for (SearchResultEntry entry : searchResult.getSearchEntries()) {
                String name = entry.getAttributeValue(GROUP_NAME_ATTR);
                result.add(name);
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
        return result;
    }


    @Override
    public LdapGroup createOrUpdateGroup(LdapGroup group) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            LdapGroup current = getGroup(group.getName(), conn);
            if (current != null) {
                Modification[] mods = new Modification[]{
                    new Modification(REPLACE, GROUP_DESC_ATTR, group.getDescription()),
                    new Modification(REPLACE, config.getLdapGroupsMemberAttr(), group.getMembersDn().toArray(new String[0]))
                };
                conn.modify(current.getDn(), mods);
                current.setDescription(group.getDescription());
                current.setMembersDn(group.getMembersDn());
            } else {
                String dn = getGroupDN(group.getName());
                Entry entry = new Entry(dn);
                entry.setAttribute(GROUP_NAME_ATTR, group.getName());
                entry.setAttribute(GROUP_DESC_ATTR, group.getDescription());
                entry.setAttribute(config.getLdapGroupsMemberAttr(), group.getMembersDn());
                entry.setAttribute("objectClass", config.getLdapGroupsObjectClass());
                conn.add(entry);
                current = new LdapGroup(dn, group.getName(), group.getDescription(), group.getMembersDn());
            }
            return current;
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public List<LdapGroup> getAccountGroups(String accountDN) throws LdapSystemException {
        List<LdapGroup> result = new ArrayList<>();
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String baseDN = config.getLdapGroupsBaseDN();
            log.info("Group base DN: " + baseDN);
            Filter filter = Filter.createANDFilter(
                    Filter.createEqualityFilter("objectClass", config.getLdapGroupsObjectClass()),
                    Filter.createEqualityFilter(config.getLdapGroupsMemberAttr(), accountDN));
            SearchResult searchResult = conn.search(baseDN, SUB, filter,
                    GROUP_NAME_ATTR, config.getLdapGroupsMemberAttr(), GROUP_DESC_ATTR);
            for (SearchResultEntry entry : searchResult.getSearchEntries()) {
                String dn = entry.getDN();
                String name = entry.getAttributeValue(GROUP_NAME_ATTR);
                String description = entry.getAttributeValue(GROUP_DESC_ATTR);
                Set<String> members = new HashSet<>(Arrays.asList(entry.getAttributeValues(config.getLdapGroupsMemberAttr())));
                result.add(new LdapGroup(name, dn, description, members));
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
        return result;
    }


    @Override
    public void addGroupMember(String accountDN, String groupName) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            LdapGroup group = getGroup(groupName, conn);
            if (group != null && group.getMembersDn().contains(accountDN)) {
                log.info("Nothing to do. Account {} is already member of group {}", accountDN, group.getName());
            } else {
                if (group == null) {
                    log.debug("Creating group {}", groupName);
                    DN groupDN = new DN(new RDN(GROUP_NAME_ATTR, groupName), new DN(config.getLdapGroupsBaseDN()));
                    Entry groupEntry = new Entry(groupDN);
                    groupEntry.addAttribute("objectClass", config.getLdapGroupsObjectClass());
                    groupEntry.addAttribute(config.getLdapGroupsMemberAttr(), accountDN);
                    conn.add(groupEntry);
                    log.info("Group {} added", groupDN);
                } else {
                    Modification mod = new Modification(ADD, config.getLdapGroupsMemberAttr(), accountDN);
                    conn.modify(new ModifyRequest(group.getDn(), mod));
                    log.info("Added membership {} to {}", accountDN, group.getName());
                }
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void deleteGroupMember(String accountDN, String groupName) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            LdapGroup group = getGroup(groupName, conn);
            if (group != null && group.getMembersDn().contains(accountDN)) {
                if (group.getMembersDn().size() == 1) {
                    log.info("Removing group {}", group.getName());
                    conn.delete(group.getDn());
                } else {
                    Modification mod = new Modification(DELETE, config.getLdapGroupsMemberAttr(), accountDN);
                    log.info("Removing membership {} from {}", accountDN, group.getName());
                    conn.modify(new ModifyRequest(group.getDn(), mod));
                }
            } else {
                log.info("Nothing to do. Account {} is not member of group {}", accountDN, groupName);
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void deleteGroup(String groupName) throws LdapSystemException {
        try (LDAPConnection conn = ldapPool.getConnection()) {
            String groupDN = getGroupDN(groupName);
            SearchResultEntry entry = conn.getEntry(groupDN, GROUP_NAME_ATTR);
            if (entry != null) {
                conn.delete(groupDN);
            }
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void deleteUser(LdapAccount account) throws LdapSystemException {
        String dn = getAccountDN(account);
        List<LdapGroup> userGroups = getAccountGroups(dn);
        for (LdapGroup group : userGroups) {
            deleteGroupMember(dn, group.getName());
        }
        log.info("Deleting LDAP accounts DN: {}", account.getDn());
        try (LDAPConnection conn = ldapPool.getConnection()) {
            conn.delete(account.getDn());
            log.info("User {} successfully deleted", account.getUsername());
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    @Override
    public void deleteUserByEmail(String email) throws LdapSystemException {
        Optional<LdapAccount> account = getAccountByEmail(email);
        account.ifPresentOrElse(this::deleteUser, () -> log.warn("Account with email {} does not exists!", email));
    }


    /**
     * Read group from LDAP. If there is no group it returns NULL.
     *
     * @param groupName name of gruop
     * @param conn ldap connection
     * @return LDAP group or null if there is no group
     * @throws LDAPException
     */
    protected LdapGroup getGroup(String groupName, LDAPConnection conn) throws LdapSystemException {
        try {
            LdapGroup result = null;
            String baseDN = config.getLdapGroupsBaseDN();
            Filter groupFilter = Filter.createEqualityFilter(GROUP_NAME_ATTR, groupName);
            SearchResultEntry entry = conn.searchForEntry(baseDN, ONE, groupFilter,
                    GROUP_NAME_ATTR, config.getLdapGroupsMemberAttr(), GROUP_DESC_ATTR);
            if (entry != null) {
                String dn = entry.getDN();
                String name = entry.getAttributeValue(GROUP_NAME_ATTR);
                String description = entry.getAttributeValue(GROUP_DESC_ATTR);
                Set<String> members = new HashSet<>(Arrays.asList(entry.getAttributeValues(config.getLdapGroupsMemberAttr())));
                result = new LdapGroup(name, dn, description, members);
            }
            return result;
        } catch (LDAPException e) {
            throw new LdapSystemException(e);
        }
    }


    private void creatOrgUnits(LDAPConnection conn) throws LDAPException {
        String peopleDn = config.getLdapUserBaseDN();
        if (conn.getEntry(peopleDn) == null) {
            Entry entry = new Entry(peopleDn);
            entry.addAttribute("objectClass", "top");
            entry.addAttribute("objectClass", "organizationalUnit");
            conn.add(entry);
        }
        String groupDn = config.getLdapGroupsBaseDN();
        if (conn.getEntry(groupDn) == null) {
            Entry entry = new Entry(groupDn);
            entry.addAttribute("objectClass", "top");
            entry.addAttribute("objectClass", "organizationalUnit");
            conn.add(entry);
        }
    }


    private String getGroupDN(String groupName) {
        return LdapAccountServiceImpl.GROUP_NAME_ATTR + "=" + groupName + "," + config.getLdapGroupsBaseDN();
    }


    private LdapAccount accountFromEntry(SearchResultEntry entry) {
        LdapAccount account = new LdapAccount(entry.getDN());
        account.setUsername(entry.getAttributeValue("cn"));
        String[] emails = entry.getAttributeValues("mail");
        account.setEmails(emails != null ? new HashSet<>(Arrays.asList(emails)) : Collections.emptySet());
        account.setName(entry.getAttributeValue("displayName"));
        account.setGivenName(entry.getAttributeValue("givenName"));
        account.setFamilyName(entry.getAttributeValue("sn"));
        account.setSubject(entry.getAttributeValue("employeeNumber"));
        account.setRole(LdapAccount.Role.valueOf(entry.getAttributeValue("employeeType")));
        return account;
    }
}
