/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Sets;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.MangoTestBase;
import com.serotonin.m2m2.MockMangoLifecycle;
import com.serotonin.m2m2.MockMangoProperties;
import com.serotonin.m2m2.MockRuntimeManager;
import com.serotonin.m2m2.db.dao.RoleDao;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.RoleVO;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.dataPoint.MockPointLocatorVO;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.dataSource.mock.MockDataSourceVO;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.permission.PermissionHolder;

/**
 * @author Terry Packer
 *
 */
public class PermissionServiceTest extends MangoTestBase {
    
    protected RoleService roleService;
    protected PermissionService permissionService;
    protected DataSourceService<MockDataSourceVO> dataSourceService;
    protected DataPointService dataPointService;
    protected UsersService usersService;
    
    protected PermissionHolder systemSuperadmin;
    
    public PermissionServiceTest() {
        super(false, 9001);
    }

    @SuppressWarnings("unchecked")
    @Before
    public void setupRoles() {
        roleService = Common.getBean(RoleService.class);
        permissionService = Common.getBean(PermissionService.class);
        dataSourceService = Common.getBean(DataSourceService.class);
        dataPointService = Common.getBean(DataPointService.class);
        usersService = Common.getBean(UsersService.class);
        systemSuperadmin = PermissionHolder.SYSTEM_SUPERADMIN;
    }
    
    MockDataSourceVO createDataSource() {
        return createDataSource(Collections.emptySet());
    }
    
    MockDataSourceVO createDataSource(RoleVO editRole) {
        return createDataSource(Collections.singleton(editRole));
    }
    
    MockDataSourceVO createDataSource(Set<RoleVO> editRoles) {
        MockDataSourceVO dsVo = new MockDataSourceVO();
        dsVo.setName("permissions_test_datasource");
        dsVo.setEditRoles(editRoles);
        return dataSourceService.insertFull(dsVo, systemSuperadmin);
    }

    DataPointVO createDataPoint() {
        return createDataPoint(Collections.emptySet(), Collections.emptySet());
    }
    
    DataPointVO createDataPoint(DataSourceVO<?> ds) {
        return createDataPoint(ds, Collections.emptySet(), Collections.emptySet()); 
    }
    
    DataPointVO createDataPoint(Set<RoleVO> readRoles, Set<RoleVO> setRoles) {
        MockDataSourceVO ds = createDataSource(Collections.emptySet());
        return createDataPoint(ds, readRoles, setRoles); 
    }
    
    DataPointVO createDataPoint(DataSourceVO<?> dsVo, Set<RoleVO> readRoles, Set<RoleVO> setRoles) {
        DataPointVO point = new DataPointVO();
        point.setDataSourceId(dsVo.getId());
        point.setName("permissions_test_datasource");
        point.setReadRoles(readRoles);
        point.setSetRoles(setRoles);
        point.setPointLocator(new MockPointLocatorVO());
        dataPointService.insertFull(point, systemSuperadmin);
        return point;
    }
    
    RoleVO randomRole() {
        RoleVO vo = new RoleVO(UUID.randomUUID().toString(), "Random permission");
        return roleService.insert(vo, PermissionHolder.SYSTEM_SUPERADMIN);
    }
    
    Set<RoleVO> randomRoles(int size) {
        Set<RoleVO> roles = new HashSet<>();
        for(int i=0; i<size; i++) {
            roles.add(randomRole());
        }
        return roles;
    }
    
    User createTestUser() {
        return createTestUser(randomRole());
    }
    
    User createTestUser(RoleVO role) {
        return createTestUser(Collections.singleton(role));
    }
    
    User createTestUser(Set<RoleVO> roles) {
        return createUser("permissions_test_user",
                "permissions_test_user",
                "permissions_test_user",
                "permissions_test_user@test.com",
                roles.toArray(new RoleVO[roles.size()]));
    }
    
    @Override
    protected MockMangoLifecycle getLifecycle() {
        MockMangoLifecycle lifecycle = super.getLifecycle();
        lifecycle.setRuntimeManager(new MockRuntimeManager(true));
        MockMangoProperties properties = new MockMangoProperties();
        properties.setDefaultValue("security.hashAlgorithm", User.BCRYPT_ALGORITHM);
        lifecycle.setProperties(properties);
        return lifecycle;
    }
    
    @Test
    public void testHasAnyRole() {
        User testUser = createTestUser();
        Set<RoleVO> roles = new HashSet<>(testUser.getRoles());
                
        assertTrue(permissionService.hasAnyRole(testUser, roles));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>()));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>(Arrays.asList(RoleDao.getInstance().getSuperadminRole()))));
        
        //Test 2 roles
        roles.add(randomRole());
        testUser.setRoles(new HashSet<>(roles));
        usersService.updateFull(testUser.getUsername(), testUser, systemSuperadmin);
        assertTrue(permissionService.hasAnyRole(testUser, roles));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>()));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>(Arrays.asList(randomRole(), randomRole()))));

        //Test 3 roles
        roles.add(randomRole());
        testUser.setRoles(new HashSet<>(roles));
        usersService.update(testUser.getUsername(), testUser, systemSuperadmin);
        assertTrue(permissionService.hasAnyRole(testUser, roles));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>()));
        assertFalse(permissionService.hasAnyRole(testUser, new HashSet<>(Arrays.asList(randomRole(), randomRole(), randomRole()))));

    }
    
    @Test
    public void ensureValidPermissionHolderOK() {
        User testUser = this.createTestUser();
        permissionService.ensureValidPermissionHolder(testUser);
    }

    @Test(expected = PermissionException.class)
    public void ensureValidPermissionHolderNull() {
        permissionService.ensureValidPermissionHolder(null);
    }

    @Test(expected = PermissionException.class)
    public void ensureValidPermissionHolderDisabled() {
        User testUser = this.createTestUser();
        testUser.setDisabled(true);
        permissionService.ensureValidPermissionHolder(testUser);
    }

    @Test
    public void isValidPermissionHolderOK() {
        User testUser = this.createTestUser();
        assertTrue(permissionService.isValidPermissionHolder(testUser));
    }

    @Test
    public void isValidPermissionHolderFailNull() {
        assertFalse(permissionService.isValidPermissionHolder(null));
    }

    @Test
    public void isValidPermissionHolderFailDisabled() {
        User testUser = this.createTestUser();
        testUser.setDisabled(true);
        assertFalse(permissionService.isValidPermissionHolder(testUser));
    }

    @Test
    public void ensureAdminRoleOK() {
        User testUser = this.createTestUser();
        testUser.setRoles(Collections.singleton(roleService.getSuperadminRole()));
        usersService.update(testUser.getUsername(), testUser, systemSuperadmin);
        permissionService.ensureAdminRole(testUser);
    }
    
    @Test(expected = PermissionException.class)
    public void ensureAdminPermissionFail() {
        User testUser = this.createTestUser();
        permissionService.ensureAdminRole(testUser);
    }

    @Test
    public void ensureDataSourcePermissionOK() {
        User testUser = this.createTestUser();
        testUser.setRoles(Collections.singleton(roleService.getSuperadminRole()));
        usersService.update(testUser.getUsername(), testUser, systemSuperadmin);
        permissionService.ensureDataSourcePermission(testUser);
    }

    @Test(expected = PermissionException.class)
    public void ensureDataSourcePermissionFail() {
        User testUser = this.createTestUser();
        permissionService.ensureDataSourcePermission(testUser);
    }

    @Test
    public void ensureDataSourcePermissionDsVoOK() {
        User testUser = this.createTestUser();
        MockDataSourceVO ds = createDataSource(testUser.getRoles());
        permissionService.ensureDataSourcePermission(testUser, ds);
    }

    @Test(expected = PermissionException.class)
    public void ensureDataSourcePermissionDsVoFail() {
        User testUser = this.createTestUser();
        MockDataSourceVO ds = createDataSource(randomRoles(1));
        permissionService.ensureDataSourcePermission(testUser, ds);
    }

    @Test
    public void ensureDataPointReadPermissionOK() {
        User testUser = this.createTestUser();
        DataPointVO dp = createDataPoint(testUser.getRoles(), Collections.emptySet());
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test(expected = PermissionException.class)
    public void ensureDataPointReadPermissionFail() {
        User testUser = this.createTestUser();
        DataPointVO dp = createDataPoint();
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointReadPermissionOkEmptyPerms() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        DataPointVO dp = createDataPoint();
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test(expected = PermissionException.class)
    public void ensureDataPointReadPermissionFailEmptyPerms() {
        User testUser = this.createTestUser();
        DataPointVO dp = createDataPoint();
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointReadPermissionOKHasDataSourcePermission() {
        MockDataSourceVO ds = createDataSource(randomRole());
        DataPointVO dp = createDataPoint(ds);
        User testUser = createTestUser(ds.getEditRoles());
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointReadPermissionOKHasSetPermission() {
        DataPointVO dp = createDataPoint(Collections.emptySet(), randomRoles(1));
        User testUser = createTestUser(dp.getSetRoles());
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointReadPermissionOKHasReadPermission() {
        DataPointVO dp = createDataPoint(randomRoles(1), Collections.emptySet());
        User testUser = createTestUser(dp.getReadRoles());
        permissionService.ensureDataPointReadPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointSetPermissionOKHasDataSourcePermission() {
        MockDataSourceVO ds = createDataSource(randomRoles(1));
        User testUser = createTestUser(ds.getEditRoles());
        DataPointVO dp = createDataPoint(ds);
        permissionService.ensureDataPointSetPermission(testUser, dp);
    }

    @Test
    public void ensureDataPointSetPermissionOKHasSetPermission() {
        DataPointVO dp = createDataPoint(Collections.emptySet(), randomRoles(1));
        User testUser = createTestUser(dp.getSetRoles());
        permissionService.ensureDataPointSetPermission(testUser, dp);
    }

    @Test(expected = PermissionException.class)
    public void ensureDataPointSetPermissionFailHasReadPermission() {
        DataPointVO dp = createDataPoint(randomRoles(1), randomRoles(1));
        User testUser = this.createTestUser(dp.getReadRoles());
        permissionService.ensureDataPointSetPermission(testUser, dp);
    }

    @Test
    public void implodeRoles() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();

        Set<RoleVO> permSet = new HashSet<>();
        permSet.add(perm1);
        permSet.add(perm2);

        String joinedPerms = permissionService.implodeRoles(permSet);

        assertTrue(joinedPerms.contains(perm1.getXid()));
        assertTrue(joinedPerms.contains(perm2.getXid()));
        assertTrue(joinedPerms.contains(","));
        assertFalse(joinedPerms.contains(" "));

        String expectedString = "";
        Iterator<RoleVO> roleIt = permSet.iterator();
        expectedString = roleIt.next().getXid() + "," + roleIt.next().getXid();
        assertTrue(expectedString.equals(joinedPerms));
    }

    @Test
    public void ensureHasSingleRoleOKSuperadmin() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureSingleRole(testUser, testUser.getRoles().iterator().next());
    }

    @Test
    public void ensureHasSingleRoleOKSuperadminNullRole() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureSingleRole(testUser, null);
    }

    @Test
    public void ensureHasSingleRoleOKHasPerm() {
        Set<RoleVO> roles = this.randomRoles(2);
        User testUser = this.createTestUser(roles);
        for(RoleVO role : roles) {
            permissionService.ensureSingleRole(testUser, role);
        }
    }

    @Test(expected = PermissionException.class)
    public void ensureHasSingleRoleFailNullRole() {
        User testUser = this.createTestUser();
        permissionService.ensureSingleRole(testUser, null);
    }

    @Test(expected = PermissionException.class)
    public void ensureHasSingleRoleFailEmptyRoles() {
        User testUser = this.createTestUser();
        RoleVO unsavedRole = new RoleVO(UUID.randomUUID().toString(), "unsaved role name");
        permissionService.ensureSingleRole(testUser, unsavedRole);
    }

    @Test
    public void ensureHasAnyRoleOKSuperadmin() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAnyRole(testUser, randomRoles(2));
    }

    @Test
    public void ensureHasAnyRoleOKSuperadminEmptySet() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAnyRole(testUser, Collections.emptySet());
    }

    @Test(expected = PermissionException.class)
    public void ensureHasAnyPermissionFailEmptySet() {
        User testUser = this.createTestUser();
        permissionService.ensureHasAnyRole(testUser, Collections.emptySet());
    }

    @Test
    public void ensureHasAnyRoleOKHasOne() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();
        RoleVO perm3 = this.randomRole();
        
        User testUser = this.createTestUser(Sets.newHashSet(perm1, perm2));

        permissionService.ensureHasAnyRole(testUser, Sets.newHashSet(perm1, perm3));
    }

    @Test
    public void ensureHasAnyRoleOKHasBoth() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();
        
        User testUser = this.createTestUser(Sets.newHashSet(perm1, perm2));

        permissionService.ensureHasAnyRole(testUser, Sets.newHashSet(perm1, perm2));
    }

    @Test(expected = PermissionException.class)
    public void ensureHasAnyPermissionFailHasNeither() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();
        RoleVO perm3 = this.randomRole();
        RoleVO perm4 = this.randomRole();
        
        User testUser = this.createTestUser(Sets.newHashSet(perm1, perm2));

        permissionService.ensureHasAnyRole(testUser, Sets.newHashSet(perm3, perm4));
    }

    @Test(expected = NullPointerException.class)
    public void ensureHasAnyPermissionFailNullSet() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAnyRole(testUser, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void ensureHasAnyPermissionFailNullEntry() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAnyRole(testUser, Collections.singleton(null));
    }

    @Test
    public void ensureHasAllPermissionsOKSuperadmin() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAllRoles(testUser, Sets.newHashSet(randomRole(), randomRole()));
    }

    @Test
    public void ensureHasAllPermissionsOKSuperadminEmptySet() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAllRoles(testUser, Collections.emptySet());
    }

    @Test(expected = PermissionException.class)
    public void ensureHasAllPermissionsFailEmptySet() {
        User testUser = this.createTestUser();
        permissionService.ensureHasAllRoles(testUser, Collections.emptySet());
    }

    @Test(expected = PermissionException.class)
    public void ensureHasAllPermissionsFailHasOne() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();

        User testUser = this.createTestUser(Sets.newHashSet(perm1, randomRole()));
        permissionService.ensureHasAllRoles(testUser, Sets.newHashSet(perm1, perm2));
    }

    @Test
    public void ensureHasAllPermissionsOKHasBoth() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();

        User testUser = this.createTestUser(Sets.newHashSet(perm1, perm2));
        permissionService.ensureHasAllRoles(testUser, Sets.newHashSet(perm1, perm2));
    }

    @Test(expected = PermissionException.class)
    public void ensureHasAllPermissionsFailHasNeither() {
        RoleVO perm1 = this.randomRole();
        RoleVO perm2 = this.randomRole();

        User testUser = this.createTestUser(Sets.newHashSet(randomRole(), randomRole()));
        permissionService.ensureHasAllRoles(testUser, Sets.newHashSet(perm1, perm2));
    }

    @Test(expected = NullPointerException.class)
    public void ensureHasAllPermissionsFailNullSet() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAllRoles(testUser, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void ensureHasAllPermissionsFailNullEntry() {
        User testUser = this.createTestUser(roleService.getSuperadminRole());
        permissionService.ensureHasAllRoles(testUser, Collections.singleton(null));
    }
}