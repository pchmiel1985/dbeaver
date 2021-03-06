/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2018 Serge Rider (serge@jkiss.org)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.debug.core.model;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.*;
import org.eclipse.debug.core.model.*;
import org.eclipse.osgi.util.NLS;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.*;
import org.jkiss.dbeaver.debug.core.DebugCore;
import org.jkiss.dbeaver.debug.internal.core.DebugCoreMessages;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseDebugTarget extends DatabaseDebugElement implements IDatabaseDebugTarget, DBGEventHandler {

    private static final Log log = Log.getLog(DatabaseDebugTarget.class);
    
    private final String modelIdentifier;

    private final ILaunch launch;
    private final IProcess process;
    private final DBGController controller;
    private final List<IThread> threads;
    private final DatabaseThread thread;

    private String name;
    private String defaultName = DebugCoreMessages.DatabaseDebugTarget_name_default;

    private boolean suspended = false;
    private boolean terminated = false;

    private Object sessionKey;

    public DatabaseDebugTarget(String modelIdentifier, ILaunch launch, IProcess process, DBGController controller) {
        super(null);
        this.modelIdentifier = modelIdentifier;
        this.launch = launch;
        this.process = process;
        this.controller = controller;
        this.controller.registerEventHandler(this);
        this.threads = new ArrayList<>();
        this.thread = newThread();
        this.threads.add(thread);

        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        IBreakpointManager breakpointManager = debugPlugin.getBreakpointManager();
        breakpointManager.addBreakpointManagerListener(this);
        breakpointManager.addBreakpointListener(this);
        debugPlugin.addDebugEventListener(this);
    }

    public IDatabaseDebugTarget getDatabaseDebugTarget() {
        return this;
    }

    @Override
    public DBGController getController() {
        return controller;
    }

    @Override
    public Object getSessionID() {
        return sessionKey;
    }

    protected DatabaseThread newThread() {
        return new DatabaseThread(this);
    }

    @Override
    public IDebugTarget getDebugTarget() {
        return this;
    }

    @Override
    public String getModelIdentifier() {
        return modelIdentifier;
    }

    @Override
    public ILaunch getLaunch() {
        return launch;
    }

    @Override
    public IProcess getProcess() {
        return process;
    }

    @Override
    public IThread[] getThreads() throws DebugException {
        return threads.toArray(new IThread[threads.size()]);
    }

    @Override
    public boolean hasThreads() throws DebugException {
        return !terminated && threads.size() > 0;
    }

    @Override
    public String getName() throws DebugException {
        if (name == null) {
            try {
                ILaunchConfiguration configuration = getLaunch().getLaunchConfiguration();
                name = getConfiguredName(configuration);
                if (name == null) {
                    name = getDefaultName();
                }
            } catch (CoreException e) {
                name = getDefaultName();
            }

        }
        return name;
    }

    protected String getConfiguredName(ILaunchConfiguration configuration) throws CoreException {
        return configuration.getName();
    }

    protected String getDefaultName() {
        return defaultName;
    }

    protected void setDefaultName(String defaultName) {
        this.defaultName = defaultName;
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            if (event.getKind() == DebugEvent.TERMINATE && event.getSource().equals(process)) {
                try {
                    terminated();
                } catch (DebugException e) {
                    log.log(e.getStatus());
                }
            }
        }
    }

    @Override
    public void connect(IProgressMonitor monitor) throws CoreException {
        try {
            sessionKey = this.controller.attach(new DefaultProgressMonitor(monitor));
        } catch (DBGException e) {
            String message = NLS.bind("Failed to connect {0} to the target", getName());
            IStatus error = DebugCore.newErrorStatus(message, e);
            process.terminate();
            throw new CoreException(error);
        }
    }

    @Override
    public boolean canTerminate() {
        return !terminated;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public void terminate() throws DebugException {
        terminated();
    }

    public synchronized void terminated() throws DebugException {
        if (!terminated) {
            threads.clear();
            terminated = true;
            suspended = false;
            try {
                controller.detach(getProgressMonitor(), sessionKey);
                controller.unregisterEventHandler(this);
            } catch (DBGException e) {
                String message = NLS.bind("Error terminating {0}", getName());
                IStatus status = DebugCore.newErrorStatus(message, e);
                throw new DebugException(status);
            } finally {
                controller.dispose();
            }
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin != null) {
                IBreakpointManager breakpointManager = debugPlugin.getBreakpointManager();
                breakpointManager.removeBreakpointListener(this);
                debugPlugin.removeDebugEventListener(this);
                breakpointManager.removeBreakpointManagerListener(this);
            }
            if (!getProcess().isTerminated()) {
                try {
                    process.terminate();
                } catch (DebugException e) {
                    // do nothing
                }
            }
            if (debugPlugin != null) {
                fireTerminateEvent();
            }
        }
    }

    @Override
    public boolean canResume() {
        return thread != null && !terminated && suspended;
    }

    @Override
    public boolean canSuspend() {
        return thread != null && !terminated && !suspended;
    }

    @Override
    public boolean isSuspended() {
        return suspended;
    }

    @Override
    public void resume() throws DebugException {
        suspended = false;
        try {
            controller.resume(sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Error resuming {0}", getName());
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
        if (thread.isSuspended()) {
            thread.resumedByTarget();
        }
        fireResumeEvent(DebugEvent.CLIENT_REQUEST);
    }

    @Override
    public void suspend() throws DebugException {
        try {
            controller.suspend(sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Error suspending {0}", getName());
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
    }

    private VoidProgressMonitor getProgressMonitor() {
        return new VoidProgressMonitor();
    }

    public void suspended(int detail) {
        suspended = true;
        thread.setStepping(false);
        thread.fireSuspendEvent(detail);
    }

    @Override
    public boolean supportsBreakpoint(IBreakpoint breakpoint) {
        return breakpoint.getModelIdentifier().equals(DebugCore.BREAKPOINT_ID_DATABASE_LINE);
    }

    @Override
    public void breakpointAdded(IBreakpoint breakpoint) {
        if (!terminated) {
            DBGBreakpointDescriptor descriptor = describeBreakpoint(breakpoint);
            if (descriptor == null) {
                String message = NLS.bind("Unable to describe breakpoint {0}", breakpoint);
                Status error = DebugCore.newErrorStatus(message);
                log.log(error);
                return;
            }
            try {
                controller.addBreakpoint(new VoidProgressMonitor(), sessionKey, descriptor);
            } catch (DBGException e) {
                String message = NLS.bind("Unable to add breakpoint {0}", breakpoint);
                Status error = DebugCore.newErrorStatus(message, e);
                log.log(error);
            }
        }
    }

    @Override
    public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (!terminated) {
            DBGBreakpointDescriptor descriptor = describeBreakpoint(breakpoint);
            if (descriptor == null) {
                String message = NLS.bind("Unable to describe breakpoint {0}", breakpoint);
                Status error = DebugCore.newErrorStatus(message);
                log.log(error);
                return;
            }
            try {
                controller.removeBreakpoint(new VoidProgressMonitor(), sessionKey, descriptor);
            } catch (DBGException e) {
                String message = NLS.bind("Unable to remove breakpoint {0}", breakpoint);
                Status error = DebugCore.newErrorStatus(message, e);
                log.log(error);
            }
        }
    }

    @Override
    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (supportsBreakpoint(breakpoint)) {
            try {
                if (breakpoint.isEnabled() && DebugPlugin.getDefault().getBreakpointManager().isEnabled()) {
                    breakpointAdded(breakpoint);
                } else {
                    breakpointRemoved(breakpoint, null);
                }
            } catch (CoreException e) {
                // do nothing
            }
        }
    }

    @Override
    public void breakpointManagerEnablementChanged(boolean enabled) {
        IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager()
                .getBreakpoints(DebugCore.BREAKPOINT_ID_DATABASE_LINE);
        for (IBreakpoint breakpoint : breakpoints) {
            if (enabled) {
                breakpointAdded(breakpoint);
            } else {
                breakpointRemoved(breakpoint, null);
            }
        }
    }

    protected DBGBreakpointDescriptor describeBreakpoint(IBreakpoint breakpoint) {
        Map<String, Object> description = new HashMap<>();
        try {
            Map<String, Object> attributes = breakpoint.getMarker().getAttributes();
            Map<String, Object> remote = DebugCore.toBreakpointDescriptor(attributes);
            description.putAll(remote);
        } catch (CoreException e) {
            log.log(e.getStatus());
            return null;
        }
        return controller.describeBreakpoint(description);
    }

    @Override
    public boolean canDisconnect() {
        return true;
    }

    @Override
    public void disconnect() throws DebugException {
        try {
            controller.detach(getProgressMonitor(), sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Error disconnecting {0}", getName());
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
    }

    @Override
    public boolean isDisconnected() {
        return false;
    }

    @Override
    public boolean supportsStorageRetrieval() {
        return false;
    }

    @Override
    public IMemoryBlock getMemoryBlock(long startAddress, long length) throws DebugException {
        return null;
    }

    @Override
    public void handleDebugEvent(DBGEvent event) {
        int kind = event.getKind();
        if (DBGEvent.SUSPEND == kind) {
            suspended(event.getDetails());
        }
        if (DBGEvent.TERMINATE == kind) {
            try {
                process.terminate();
            } catch (DebugException e) {
                log.log(e.getStatus());
            }
        }
    }

    public boolean canStepInto() {
        return controller.canStepInto(sessionKey);
    }

    public boolean canStepOver() {
        return controller.canStepOver(sessionKey);
    }

    public boolean canStepReturn() {
        return controller.canStepReturn(sessionKey);
    }

    public void stepInto() throws DebugException {
        DBGController controller = getController();
        try {
            controller.stepInto(sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Step into failed for session {0}", sessionKey);
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
    }

    public void stepOver() throws DebugException {
        DBGController controller = getController();
        try {
            controller.stepOver(sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Step over failed for session {0}", sessionKey);
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
    }

    public void stepReturn() throws DebugException {
        DBGController controller = getController();
        try {
            controller.stepReturn(sessionKey);
        } catch (DBGException e) {
            String message = NLS.bind("Step return failed for session {0}", sessionKey);
            IStatus status = DebugCore.newErrorStatus(message, e);
            throw new DebugException(status);
        }
    }

}
