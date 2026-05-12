package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.application.admin.UserAdminFacade;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMembershipResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private UserAdminFacade userAdminFacade;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userAdminFacade = mock(UserAdminFacade.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userAdminFacade))
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    @Test
    void listGroupsByUser_returnsPagedResult() throws Exception {
        when(userAdminFacade.listGroupsByUserPk(eq(101L), eq(UiPageRequest.of(0, 20))))
                .thenReturn(Optional.of(PagedResponse.of(
                        List.of(new UserGroupMembershipResponse(101L, 11L, "ADMIN", "관리자")),
                        0,
                        20,
                        1L
                )));

        mockMvc.perform(get("/api/user/101/group")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].userPk").value(101))
                .andExpect(jsonPath("$.data.items[0].groupId").value(11))
                .andExpect(jsonPath("$.data.items[0].groupCode").value("ADMIN"))
                .andExpect(jsonPath("$.data.items[0].groupName").value("관리자"))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(userAdminFacade).listGroupsByUserPk(eq(101L), eq(UiPageRequest.of(0, 20)));
    }

    @Test
    void listGroupsByUser_returnsNotFoundWhenUserMissing() throws Exception {
        when(userAdminFacade.listGroupsByUserPk(eq(999L), eq(UiPageRequest.of(0, 20))))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/user/999/group")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void listGroupsByUser_appliesDefaultPaging() throws Exception {
        when(userAdminFacade.listGroupsByUserPk(eq(101L), eq(UiPageRequest.of(0, 100))))
                .thenReturn(Optional.of(PagedResponse.empty(0, 100)));

        mockMvc.perform(get("/api/user/101/group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(userAdminFacade).listGroupsByUserPk(eq(101L), eq(UiPageRequest.of(0, 100)));
    }
}
