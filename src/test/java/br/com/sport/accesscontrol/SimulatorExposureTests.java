package br.com.sport.accesscontrol;

import br.com.sport.accesscontrol.events.AccessEventService;
import br.com.sport.accesscontrol.events.AccessEventSimulatorController;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasSimulatorController;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasSimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimulatorExposureTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(IntelbrasSimulatorService.class, () -> mock(IntelbrasSimulatorService.class))
            .withBean(AccessEventService.class, () -> mock(AccessEventService.class))
            .withUserConfiguration(IntelbrasSimulatorController.class, AccessEventSimulatorController.class);

    @Test
    void simulatorControllersAreNotRegisteredWhenDisabled() {
        contextRunner
                .withPropertyValues("app.simulator.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IntelbrasSimulatorController.class);
                    assertThat(context).doesNotHaveBean(AccessEventSimulatorController.class);
                });
    }

    @Test
    void simulatorControllersAreRegisteredForDevAndTestsByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(IntelbrasSimulatorController.class);
            assertThat(context).hasSingleBean(AccessEventSimulatorController.class);
        });
    }

    @Test
    void simulatorControllersCanBeExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("app.simulator.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(IntelbrasSimulatorController.class);
                    assertThat(context).hasSingleBean(AccessEventSimulatorController.class);
                });
    }
}
