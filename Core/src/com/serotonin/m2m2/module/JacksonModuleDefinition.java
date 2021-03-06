/*
   Copyright (C) 2016 Infinite Automation Systems Inc. All rights reserved.
   @author Terry Packer
 */
package com.serotonin.m2m2.module;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Define Jackson Modules to be applied to the various core mappers
 * 
 * @author Terry Packer
 *
 */
abstract public class JacksonModuleDefinition extends ModuleElementDefinition {
    
    public enum ObjectMapperSource {
        REST, //for use in the REST mapper
        DATABASE, //for use in the Database mapper
        COMMON //for use in the Common mapper Common.objectMapper
    }
	
	/**
	 * Get the Jackson Module to apply to the Mapper
	 */
	public abstract SimpleModule getJacksonModule();

	public abstract ObjectMapperSource getSourceMapperType();
	
}
