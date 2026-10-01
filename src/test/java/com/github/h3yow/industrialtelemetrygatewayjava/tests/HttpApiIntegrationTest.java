package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经 HTTP 验证：正常读写、过期写入 409、设备不存在 404、
 * 越界值 400、断连 503，错误体只含归一化分类，不含通道原始类名。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deviceListAndSnapshot() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceIds[0]").value("plc-line-1"));

        mockMvc.perform(get("/api/devices/plc-line-1/points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("plc-line-1"))
                .andExpect(jsonPath("$.commitSeq").exists())
                .andExpect(jsonPath("$.samples.length()").value(4));
    }

    @Test
    void staleWriteReturns409WithStaleVersionCategory() throws Exception {
        // 用独立点位 level-mm，避免与其他用例共享上下文状态
        // 先把版本推进到 1
        mockMvc.perform(post("/api/devices/plc-line-2/points/level-mm/writes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":8000,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        // 再基于过期版本 0 写
        mockMvc.perform(post("/api/devices/plc-line-2/points/level-mm/writes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":9999,\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.actualVersion").value(1))
                .andExpect(jsonPath("$.currentValue").value(8000));
    }

    @Test
    void outOfRangeAndMissingDeviceAreClassified() throws Exception {
        mockMvc.perform(post("/api/devices/plc-line-1/points/motor-speed-rpm/writes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":70000,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALUE_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                "ValueOutOfBoundsException"))));

        mockMvc.perform(get("/api/devices/no-such-device/points"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("DEVICE_NOT_FOUND"));

        mockMvc.perform(get("/api/devices/plc-line-1/points/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("INVALID_ADDRESS"));
    }

    @Test
    void disconnectedDeviceReturns503ThenRecovers() throws Exception {
        mockMvc.perform(post("/api/devices/plc-line-1/disconnect"))
                .andExpect(status().isOk());
        try {
            mockMvc.perform(get("/api/devices/plc-line-1/points/motor-speed-rpm"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.category").value("DEVICE_DISCONNECTED"));
        } finally {
            mockMvc.perform(post("/api/devices/plc-line-1/connect"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/devices/plc-line-1/points/motor-speed-rpm"))
                .andExpect(status().isOk());
    }

    @Test
    void atomicBatchWriteCommitsOrRejectsTogether() throws Exception {
        // 整批成功
        mockMvc.perform(post("/api/devices/plc-line-1/points/batch-writes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"motor-speed-rpm\",\"value\":1234},"
                                + "{\"name\":\"pressure-pa\",\"value\":2222}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.outcomes.length()").value(2));

        // 快照里两个点位都应是同一次提交后的新版本 1
        mockMvc.perform(get("/api/devices/plc-line-1/points"))
                .andExpect(jsonPath("$.samples[?(@.name=='motor-speed-rpm')].version")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.samples[?(@.name=='pressure-pa')].version")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }
}
