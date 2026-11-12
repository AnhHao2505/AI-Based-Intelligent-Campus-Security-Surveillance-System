package com.fa26se040.icss.service;



import com.fa26se040.icss.entity.Area;

import com.fa26se040.icss.entity.Floor;

import com.fa26se040.icss.enums.AreaLevel;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MvcResult;



import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.UUID;

import java.util.stream.StreamSupport;



import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;



/**

 * BR-AR-COORD-01..03: toạ độ trung tâm khu vực là tuỳ chọn. Trống cả hai -> lưu null ("chưa định vị", không có pin

 * bản đồ); chỉ có một trong hai -> 400 ERR_AREA_006; quy tắc giống nhau cho tạo (POST) và sửa (PUT).

 */

class AreaCenterCoordinatesTest extends Step5bTestSupport {



    private static final String PAIR_MESSAGE = "Phải nhập đủ cả vĩ độ và kinh độ";

    private static final String LAT_MESSAGE = "Vĩ độ phải từ -90 đến 90";

    private static final String LNG_MESSAGE = "Kinh độ phải từ -180 đến 180";



    @BeforeEach

    void useShortFloorName() {

        // Tầng của Step5bTestSupport ("Tầng test 5b <suffix>", 21 ký tự) vượt giới hạn 20 ký tự của request khu vực

        floor = floorRepository.save(Floor.builder()

                .name("TD " + suffix)

                .floorOrder(1)

                .building(building)

                .isActive(true)

                .build());

    }



    private Map<String, Object> createBody(String name, Double lat, Double lng) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("name", name);

        body.put("areaLevel", AreaLevel.CONFIDENTIAL.name());

        body.put("building", building.getName());

        body.put("floor", floor.getName());

        body.put("floorId", floor.getId());

        body.put("centerLatitude", lat);

        body.put("centerLongitude", lng);

        return body;

    }



    private Map<String, Object> updateBody(Area area, Double lat, Double lng) throws Exception {

        Map<String, Object> body = createBody(area.getName(), lat, lng);

        body.put("areaLevel", area.getAreaLevel().name());

        body.put("version", apiVersion(area));

        return body;

    }



    private MvcResult create(Double lat, Double lng, String name) throws Exception {

        return send(post("/api/areas"), admin, createBody(name, lat, lng)).andReturn();

    }



    private Area createdArea(MvcResult r) throws Exception {

        UUID id = UUID.fromString(json(r).path("data").path("id").asText());

        return areaRepository.findById(id).orElseThrow();

    }



    private boolean areaNameExists(String name) {

        return areaRepository.findAll().stream().anyMatch(a -> name.equals(a.getName()));

    }



    private Area areaWithCoordinates() {

        Area a = newArea(AreaLevel.CONFIDENTIAL);

        assertNotNull(a.getCenterLatitude(), "Tiền điều kiện: newArea gán toạ độ");

        return a;

    }



    @Test

    @DisplayName("BR-AREA-MAP-01: tạo khu vực, vĩ độ và kinh độ đều null -> 400 VALIDATION_ERROR, không tạo")

    void createWithBothNull_rejected() throws Exception {

        String name = "Toa do trong " + suffix;

        MvcResult r = create(null, null, name);



        assertEquals(400, status(r), describe(r));

        assertEquals("VALIDATION_ERROR", errorCode(r));

        assertEquals("Vĩ độ không được để trống", json(r).path("data").path("centerLatitude").asText());

        assertEquals("Kinh độ không được để trống", json(r).path("data").path("centerLongitude").asText());

        assertFalse(areaNameExists(name), "Bị từ chối thì không tạo khu vực");

    }



    @Test

    @DisplayName("BR-AR-COORD-02: tạo khu vực chỉ có vĩ độ -> 400 VALIDATION_ERROR, không tạo")

    void createWithOnlyLatitude_rejected() throws Exception {

        String name = "Toa do thieu " + suffix;

        MvcResult r = create(10.84, null, name);



        assertEquals(400, status(r), describe(r));

        assertEquals("VALIDATION_ERROR", errorCode(r));

        assertEquals("Kinh độ không được để trống", json(r).path("data").path("centerLongitude").asText());

        assertFalse(areaNameExists(name), "Bị từ chối thì không tạo khu vực");

    }



    @Test

    @DisplayName("BR-AR-COORD-01: tạo khu vực đủ cả hai toạ độ hợp lệ -> 201, đúng giá trị")

    void createWithBothValid_savesValues() throws Exception {

        MvcResult r = create(10.8418, 106.81, "Toa do du " + suffix);



        assertEquals(201, status(r), describe(r));

        Area saved = createdArea(r);

        assertEquals(10.8418, saved.getCenterLatitude(), 1e-9);

        assertEquals(106.81, saved.getCenterLongitude(), 1e-9);

    }



    @Test

    @DisplayName("BR-AR-COORD-03: sửa khu vực đang có toạ độ, xoá trắng cả hai -> 400 ERR_AREA_006")

    void updateClearingBoth_rejected() throws Exception {

        Area area = areaWithCoordinates();



        MvcResult r = send(put("/api/areas/{id}", area.getId()), admin, updateBody(area, null, null)).andReturn();



        assertEquals(400, status(r), describe(r));

        assertEquals("ERR_AREA_006", errorCode(r));

        assertEquals(PAIR_MESSAGE, message(r));

    }



    @Test

    @DisplayName("BR-AR-COORD-02/03: sửa khu vực chỉ có kinh độ -> 400 ERR_AREA_006, toạ độ cũ giữ nguyên")

    void updateWithOnlyLongitude_rejected() throws Exception {

        Area area = areaWithCoordinates();

        Double latBefore = area.getCenterLatitude();

        Double lngBefore = area.getCenterLongitude();



        MvcResult r = send(put("/api/areas/{id}", area.getId()), admin, updateBody(area, null, 106.82)).andReturn();



        assertEquals(400, status(r), describe(r));

        assertEquals("ERR_AREA_006", errorCode(r));

        assertEquals(PAIR_MESSAGE, message(r));

        Area after = reload(area);

        assertEquals(latBefore, after.getCenterLatitude());

        assertEquals(lngBefore, after.getCenterLongitude());

    }



    @Test

    @DisplayName("Vĩ độ 91 -> 400 (giới hạn -90..90 như cũ)")

    void latitudeOutOfRange_rejected() throws Exception {

        String name = "Toa do sai " + suffix;

        MvcResult r = create(91.0, 106.81, name);



        assertEquals(400, status(r), describe(r));

        assertFalse(areaNameExists(name));

    }



    @Test

    @DisplayName("BR-AR-23: tạo / sửa khu vực với vĩ độ 91 hoặc kinh độ 181 -> 400 VALIDATION_ERROR, không lưu")

    void coordinateOutOfRange_createAndUpdate_validationError() throws Exception {

        String latName = "Vi do 91 " + suffix;

        MvcResult createLat = create(91.0, 106.81, latName);

        assertEquals(400, status(createLat), describe(createLat));

        assertEquals("VALIDATION_ERROR", errorCode(createLat));

        assertEquals(LAT_MESSAGE, json(createLat).path("data").path("centerLatitude").asText());

        assertFalse(areaNameExists(latName));



        String lngName = "Kinh do 181 " + suffix;

        MvcResult createLng = create(10.84, 181.0, lngName);

        assertEquals(400, status(createLng), describe(createLng));

        assertEquals("VALIDATION_ERROR", errorCode(createLng));

        assertEquals(LNG_MESSAGE, json(createLng).path("data").path("centerLongitude").asText());

        assertFalse(areaNameExists(lngName));



        Area area = areaWithCoordinates();

        Double latBefore = area.getCenterLatitude();

        Double lngBefore = area.getCenterLongitude();



        MvcResult updateLat = send(put("/api/areas/{id}", area.getId()), admin, updateBody(area, 91.0, 106.81)).andReturn();

        assertEquals(400, status(updateLat), describe(updateLat));

        assertEquals("VALIDATION_ERROR", errorCode(updateLat));

        assertEquals(LAT_MESSAGE, json(updateLat).path("data").path("centerLatitude").asText());



        MvcResult updateLng = send(put("/api/areas/{id}", area.getId()), admin, updateBody(area, 10.84, 181.0)).andReturn();

        assertEquals(400, status(updateLng), describe(updateLng));

        assertEquals("VALIDATION_ERROR", errorCode(updateLng));

        assertEquals(LNG_MESSAGE, json(updateLng).path("data").path("centerLongitude").asText());



        Area after = reload(area);

        assertEquals(latBefore, after.getCenterLatitude());

        assertEquals(lngBefore, after.getCenterLongitude());

    }



    @Test

    @DisplayName("BR-AR-COORD-01: khu vực chưa định vị (null) không có trong API pin bản đồ; khu vực có toạ độ thì có")

    void unlocatedAreaNotInMapPins() throws Exception {

        Area unlocated = areaRepository.save(Area.builder()

                .name("Chua dinh vi " + suffix)

                .areaLevel(AreaLevel.CONFIDENTIAL)

                .building(building.getName())

                .floor(floor.getName())

                .floorEntity(floor)

                .isActive(true)

                .build());

        Area located = areaWithCoordinates();



        MvcResult r = send(get("/api/areas/map-pins"), admin, null).andReturn();

        assertEquals(200, status(r), describe(r));

        JsonNode data = json(r).path("data");

        List<String> ids = StreamSupport.stream(data.spliterator(), false)

                .map(n -> n.path("id").asText())

                .toList();



        assertFalse(ids.contains(unlocated.getId().toString()), "Khu vực null không được có pin");

        assertTrue(ids.contains(located.getId().toString()), "Khu vực có toạ độ phải có pin");

    }


    @Test
    @DisplayName("U1 Happy Path: sửa tên/cấp khu vực đang chưa gắn block 3D (null/null) với null/null -> 200 OK, giữ null")
    void updateUnlocatedAreaKeepingNullPair_succeeds() throws Exception {
        Area unlocated = areaRepository.save(Area.builder()
                .name("Chua dinh vi U1 " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .isActive(true)
                .build());

        Map<String, Object> body = updateBody(unlocated, null, null);
        String newName = "Da doi ten U1 " + suffix;
        body.put("name", newName);

        MvcResult r = send(put("/api/areas/{id}", unlocated.getId()), admin, body).andReturn();

        assertEquals(200, status(r), describe(r));
        Area after = reload(unlocated);
        assertEquals(newName, after.getName());
        assertNull(after.getCenterLatitude(), "Vẫn giữ trạng thái chưa gắn block 3D");
        assertNull(after.getCenterLongitude(), "Vẫn giữ trạng thái chưa gắn block 3D");
    }

    @Test
    @DisplayName("U1 Validation: khu vực đang null/null gửi nửa cặp (chỉ vĩ độ) -> 400 ERR_AREA_006")
    void updateUnlocatedAreaWithHalfPair_rejected() throws Exception {
        Area unlocated = areaRepository.save(Area.builder()
                .name("Nua cap U1 " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .isActive(true)
                .build());

        MvcResult r = send(put("/api/areas/{id}", unlocated.getId()), admin, updateBody(unlocated, 10.84, null)).andReturn();

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_006", errorCode(r));
        assertEquals(PAIR_MESSAGE, message(r));
        Area after = reload(unlocated);
        assertNull(after.getCenterLatitude());
        assertNull(after.getCenterLongitude());
    }

    @Test
    @DisplayName("U1 Bind: khu vực đang null/null gán đủ cặp toạ độ block 3D -> 200 OK, lưu toạ độ")
    void updateUnlocatedAreaBindingCoordinates_succeeds() throws Exception {
        Area unlocated = areaRepository.save(Area.builder()
                .name("Gan block U1 " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .isActive(true)
                .build());

        MvcResult r = send(put("/api/areas/{id}", unlocated.getId()), admin, updateBody(unlocated, 10.8418, 106.81)).andReturn();

        assertEquals(200, status(r), describe(r));
        Area after = reload(unlocated);
        assertEquals(10.8418, after.getCenterLatitude(), 1e-9);
        assertEquals(106.81, after.getCenterLongitude(), 1e-9);
    }
}
