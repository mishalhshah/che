/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.extension.machine.client.command.macros;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.machine.shared.dto.MachineDto;
import org.eclipse.che.api.machine.shared.dto.ServerDto;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.js.Promises;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.command.macro.CommandMacro;
import org.eclipse.che.ide.api.command.macro.CommandMacroRegistry;
import org.eclipse.che.ide.api.machine.MachineServiceClient;
import org.eclipse.che.ide.api.machine.events.WsAgentStateEvent;
import org.eclipse.che.ide.api.machine.events.WsAgentStateHandler;

import java.util.Map;
import java.util.Set;

/**
 * Provide mapping internal port, i.e. ${server.port.8080} to 127.0.0.1:21212.
 *
 * @author Vlad Zhukovskiy
 */
@Singleton
public class ServerPortProvider implements WsAgentStateHandler {

    public static final String KEY_TEMPLATE = "${server.port.%}";

    private final MachineServiceClient machineServiceClient;
    private final CommandMacroRegistry commandPropertyRegistry;
    private final AppContext           appContext;

    private Set<CommandMacro> providers;

    private final Operation<MachineDto> registerProviders = new Operation<MachineDto>() {
        @Override
        public void apply(MachineDto machine) throws OperationException {
            providers = getProviders(machine);
            commandPropertyRegistry.register(providers);
        }
    };

    @Inject
    public ServerPortProvider(EventBus eventBus,
                              MachineServiceClient machineServiceClient,
                              CommandMacroRegistry commandPropertyRegistry,
                              AppContext appContext) {
        this.machineServiceClient = machineServiceClient;
        this.commandPropertyRegistry = commandPropertyRegistry;
        this.appContext = appContext;

        eventBus.addHandler(WsAgentStateEvent.TYPE, this);

        registerProviders();
    }

    private void registerProviders() {
        String devMachineId = appContext.getDevMachine().getId();
        if (devMachineId != null) {
            machineServiceClient.getMachine(appContext.getWorkspaceId(), devMachineId).then(registerProviders);
        }
    }

    private Set<CommandMacro> getProviders(MachineDto machine) {
        Set<CommandMacro> providers = Sets.newHashSet();
        for (Map.Entry<String, ServerDto> entry : machine.getRuntime().getServers().entrySet()) {
            providers.add(new AddressProvider(entry.getKey(),
                                              entry.getValue().getAddress(),
                                              entry.getKey()));

            if (entry.getKey().endsWith("/tcp")) {
                providers.add(new AddressProvider(entry.getKey().substring(0, entry.getKey().length() - 4),
                                                  entry.getValue().getAddress(), entry.getKey()));
            }
        }

        return providers;
    }

    @Override
    public void onWsAgentStarted(WsAgentStateEvent event) {
        registerProviders();
    }

    @Override
    public void onWsAgentStopped(WsAgentStateEvent event) {
        for (CommandMacro provider : providers) {
            commandPropertyRegistry.unregister(provider);
        }

        providers.clear();
    }

    private class AddressProvider implements CommandMacro {

        String variable;
        String address;
        String description;

        AddressProvider(String internalPort, String address, String description) {
            this.variable = KEY_TEMPLATE.replaceAll("%", internalPort);
            this.address = address;
            this.description = description;
        }

        @Override
        public String getName() {
            return variable;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Promise<String> expand() {
            return Promises.resolve(address);
        }
    }
}