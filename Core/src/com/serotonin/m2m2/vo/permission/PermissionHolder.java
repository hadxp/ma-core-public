/*
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.m2m2.vo.permission;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.serotonin.m2m2.vo.role.Role;

/**
 * Something that holds permissions, typically a user. Could however in the future be groups, data source scripts etc.
 *
 * @author Jared Wiltshire
 */
public interface PermissionHolder {

    public static final String SUPERADMIN_ROLE_XID = "superadmin";
    public static final String USER_ROLE_XID = "user";

    /**
     * The superadmin role from the database upon initialization
     */
    public static final Role SUPERADMIN_ROLE = new Role(1, SUPERADMIN_ROLE_XID);
    /**
     * The user role from the database upon initialization
     */
    public static final Role USER_ROLE = new Role(2, USER_ROLE_XID);

    /**
     * Represents the Mango system and is a member of the superadmin role. This PermissionHolder should only be used in scenarios
     * where the code needs to use a service that requires a PermissionHolder but there is no user currently logged in. i.e. for
     * background processes or operations such as resetting a user's password.
     *
     * Note: When working in a Spring service or controller prefer injecting
     * @Qualifier(SYSTEM_SUPERADMIN_PERMISSION_HOLDER) PermissionHolder
     */
    public static final PermissionHolder SYSTEM_SUPERADMIN = new PermissionHolder() {

        final Set<Role> roles = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(SUPERADMIN_ROLE, USER_ROLE)));

        @Override
        public String getPermissionHolderName() {
            return "SYSTEM_SUPERADMIN";
        }

        @Override
        public boolean isPermissionHolderDisabled() {
            return false;
        }

        @Override
        public Set<Role> getAllInheritedRoles() {
            return roles;
        }
    };

    /**
     * @return a name for the permission holder, typically the username
     */
    String getPermissionHolderName();

    /**
     * @return true if permission holder is disabled (i.e. all permission checks should fail)
     */
    boolean isPermissionHolderDisabled();

    /**
     * The roles for this permission holder, including all inherited roles
     * @return
     */
    Set<Role> getAllInheritedRoles();

}
