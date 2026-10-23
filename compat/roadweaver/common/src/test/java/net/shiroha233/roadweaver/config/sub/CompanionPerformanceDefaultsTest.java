package net.shiroha233.roadweaver.config.sub;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPerformanceDefaultsTest {
    @Test void boundedSharedPoolUsesFullDutyByDefaultButPreservesAnExistingChoice() {
        assertEquals(100,new PerformanceConfig().threadDutyCycle());
        PerformanceConfig existing=new Gson().fromJson("{\"threadDutyCycle\":50}",PerformanceConfig.class);
        existing.sanitize();
        assertEquals(50,existing.threadDutyCycle());
    }
}
