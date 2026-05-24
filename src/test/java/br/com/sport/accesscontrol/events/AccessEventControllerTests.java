package br.com.sport.accesscontrol.events;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessEventControllerTests {

    @Test
    void findAllWithoutQueryParamsUsesPagedSearchDefaults() {
        var service = mock(AccessEventService.class);
        when(service.search(any())).thenReturn(new AccessEventPageResponse(List.of(), 0, 50, 0, 0));
        var controller = new AccessEventController(service);

        var response = controller.findAll(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );

        assertThat(response).isInstanceOf(AccessEventPageResponse.class);
        var page = (AccessEventPageResponse) response;
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        verify(service).search(new AccessEventSearchRequest(
                0, 50, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        ));
        verify(service, never()).findAll();
    }
}
