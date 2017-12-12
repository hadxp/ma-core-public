/**
 * Copyright (C) 2017 Infinite Automation Software. All rights reserved.
 *
 */
package com.serotonin.m2m2.web.mvc.spring.security;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.web.mvc.spring.security.authentication.MangoPasswordAuthenticationProvider;

/**
 * 
 * Customize Session Registration Actions
 * 
 * @author Terry Packer
 */
public class MangoSessionRegistry extends SessionRegistryImpl {
    
    /**
     * Return a count of all active sessions.
     * 
     * @return
     */
    public int getActiveSessionCount(){
        int activeCount = 0;
        final List<Object> allPrincipals = this.getAllPrincipals();

        for (final Object principal : allPrincipals) {
            if (principal instanceof User) {
                activeCount += this.getAllSessions(principal, false).size();
            }
        }
        
        return activeCount;
    }
    

    /**
     * Expires the http sessions for a user.
     * The sessions are located by the user's id (the session registry stores Users in a Map, User.equals() and User.hashCode() only compare the id).
     * Note that if you wish to keep the current http session active you should register a new session and set the security context. See UserRestController.
     * 
     * @param user - cannot be null
     */
    public void exireSessionsForUser(User user) {
        List<SessionInformation> userSessions = this.getAllSessions(user, false);
        for (SessionInformation info : userSessions) {
            info.expireNow();
        }
    }
    
    /**
     * This method should be called if a user is updated via HTTP (e.g. via our UserRestController).
     * If the user's ID is the same as the current HTTP user's ID then the Spring Security context and
     * session attributes will be updated.
     * 
     * @param request
     * @param user
     */
    public void userUpdated(HttpServletRequest request, User user) {
        User currentUser = Common.getHttpUser();
        if (currentUser == null || currentUser.getId() != user.getId()) {
            return;
        }
        
        Authentication currentAuthentication = SecurityContextHolder.getContext().getAuthentication();
        
        
        HttpSession session = request.getSession(false);
        if (session != null) {
            SessionInformation info = this.getSessionInformation(session.getId());
            if (info == null) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Unknown session " + session.getId());
                }
                return;
            } else if (info.isExpired()) {
                // Session was set to expire via a call to exireSessionsForUser() from the DAO.
                // Invalidate the current session and register a new one right now so the user can continue working.
                
                // Copy all attributes as per SessionFixationProtectionStrategy
                Enumeration<String> names = session.getAttributeNames();
                Map<String, Object> attributes = new HashMap<>();
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    attributes.put(name, session.getAttribute(name));
                }

                this.removeSessionInformation(session.getId());
                session.invalidate();
                
                HttpSession newSession = request.getSession(true);
                this.registerNewSession(newSession.getId(), user);
                
                for (Entry<String, Object> entry : attributes.entrySet()) {
                    newSession.setAttribute(entry.getKey(), entry.getValue());
                }
                
                session = newSession;
            }
            
            // update the session attribute for legacy pages with the new user
            session.setAttribute(Common.SESSION_USER, user);
        }
        
        // Set the spring security context (thread local) to a new Authentication with the updated user and authorities.
        // Updates the SPRING_SECURITY_CONTEXT session attribute as well.
        // Should always be a UsernamePasswordAuthenticationToken a user cannot update themselves via a JWT.
        if (currentAuthentication instanceof UsernamePasswordAuthenticationToken) {
            UsernamePasswordAuthenticationToken newAuthentication = MangoPasswordAuthenticationProvider.createAuthenticatedToken(user);
            SecurityContextHolder.getContext().setAuthentication(newAuthentication);
        }
        
    }
}
