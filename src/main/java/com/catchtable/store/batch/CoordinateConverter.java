package com.catchtable.store.batch;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

// 인허가 데이터의 X,Y좌표(EPSG:5179, UTM-K 미터)를 위도,경도(WGS84)로 바꾼다
@Component
public class CoordinateConverter {

    private static final String UTMK =
            "+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 +units=m +no_defs";
    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    private final CoordinateTransform transform;

    public CoordinateConverter() {
        CRSFactory factory = new CRSFactory();
        this.transform = new CoordinateTransformFactory().createTransform(
                factory.createFromParameters("EPSG:5179", UTMK),
                factory.createFromParameters("EPSG:4326", WGS84));
    }

    // 반환: [위도, 경도]
    public double[] toLatLng(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        transform.transform(new ProjCoordinate(x, y), out);
        return new double[]{out.y, out.x};   // proj4j는 x=경도, y=위도
    }
}
