package cn.zzuit.campus.reservation;

import cn.zzuit.campus.common.ApiException;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Names and IDs mirror the existing scene definitions; this is not a room inventory. */
@Service
public class ReservationPlaceCatalog {
    private final List<Place> places;
    public ReservationPlaceCatalog() {
        try (var stream=new ClassPathResource("campus/reservation-places.json").getInputStream()) {
            places=List.copyOf(Arrays.asList(JsonMapper.builder().build().readValue(stream,Place[].class)));
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot read reservation place catalog",ex); }
    }
    public List<Place> places() { return places; }
    public String validatedId(String campusCode,String id) {
        if (id==null || id.isBlank()) return null;
        return places.stream().filter(p->p.campusCode().equals(campusCode) && p.id().equals(id.trim())).findFirst()
            .orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CAMPUS_PLACE","地图地点不存在或不属于所选校区")).id();
    }
    public record Place(String campusCode,String id,String name) {}
}
