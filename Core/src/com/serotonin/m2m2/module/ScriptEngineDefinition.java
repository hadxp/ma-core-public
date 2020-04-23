/*
 * Copyright (C) 2020 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.m2m2.module;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

import org.springframework.beans.factory.annotation.Autowired;

import com.infiniteautomation.mango.permission.MangoPermission;
import com.infiniteautomation.mango.spring.script.MangoScript;
import com.infiniteautomation.mango.spring.service.PermissionService;

/**
 * Customizes a ScriptEngine and applies security restrictions based on roles.
 *
 * @author Jared Wiltshire
 */
public abstract class ScriptEngineDefinition extends ModuleElementDefinition {

    @Autowired
    protected PermissionService permissionService;

    public abstract MangoPermission requiredPermission();
    public abstract boolean supports(ScriptEngineFactory engineFactory);

    public ScriptEngine createEngine(ScriptEngineFactory engineFactory, MangoScript script) {
        return engineFactory.getScriptEngine();
    }

    public Object toScriptNative(Object value) {
        return value;
    }
}