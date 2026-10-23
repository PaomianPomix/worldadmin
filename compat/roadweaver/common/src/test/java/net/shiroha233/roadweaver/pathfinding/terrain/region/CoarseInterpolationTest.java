package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseInterpolationTest {
    @Test void bilinearFieldPreservesAPlaneBetweenSamplesIncludingNegativeCoordinates() {
        short[] plane = {4, 36, 68, 100}; // height = 100 + x + 2*z, origin (-32,-32)
        assertEquals(44, CoarseInterpolation.height(plane, 2, 2, -32, -32, 32, -24, -16));
        assertEquals(4, CoarseInterpolation.height(plane, 2, 2, -32, -32, 32, -32, -32));
        assertEquals(100, CoarseInterpolation.height(plane, 2, 2, -32, -32, 32, 0, 0));
    }

    @Test void samplesClampAtOuterEdgesAndSingleSampleFieldsRemainValid() {
        short[] values = {0,64,128,192};
        assertEquals(96, CoarseInterpolation.height(values,2,2,0,0,32,16,16));
        assertEquals(128, CoarseInterpolation.height(values,2,2,0,0,32,-100,100));
        assertEquals(-40, CoarseInterpolation.height(new short[]{-40},1,1,0,0,32,100,-100));
    }

    @Test void waterIsConservativeBetweenCornersButExactGridFlagsRemainExact() {
        byte[] flags = {0,1,2,4};
        assertEquals(7, CoarseInterpolation.flags(flags,2,2,0,0,32,1,1));
        assertEquals(0, CoarseInterpolation.flags(flags,2,2,0,0,32,0,0));
        assertEquals(1, CoarseInterpolation.flags(flags,2,2,0,0,32,32,0));
        assertEquals(3, CoarseInterpolation.flags(new byte[]{1,2,0,0},2,2,0,0,32,8,0));
    }

    @Test void adjacentTilesAgreeAtTheSeamAndBundleMatchesIndividualQueries() {
        CoarseTerrainTile left = tile(-1,-1);
        CoarseTerrainTile right = tile(0,-1);
        assertEquals(973, left.height(-1,-13));
        assertEquals(974, left.height(0,-13));
        assertEquals(left.height(0,-13), right.height(0,-13));
        assertEquals(975, right.height(1,-13));
        assertTrue(left.isColumnWater(-1,-16));
        assertTrue(right.isColumnWater(1,-16));
        var bundle = right.sampleBundle(1,-16);
        assertEquals(right.height(1,-16), bundle.height());
        assertEquals(right.oceanFloor(1,-16), bundle.oceanFloor());
        assertEquals(right.isColumnWater(1,-16), bundle.columnWater());
        assertEquals(right.isWaterBiome(1,-16), bundle.waterBiome());
    }

    @Test void arrayBackedRegionsUseTheSameInterpolatedContractAndKeepOutsideBoundsEmpty() {
        var id=ResourceLocation.fromNamespaceAndPath("worldadmin","test");
        var bounds=CoarseRegionBounds.aligned(id,0,0,32,32,32);
        var region=new CoarseTerrainRegion(bounds,100,new short[]{0,64,128,192},
                new short[]{-16,48,112,176},new byte[]{0,1,2,0},new int[4]);
        assertEquals(96,region.height(16,16));
        assertEquals(80,region.oceanFloor(16,16));
        assertEquals(20,region.sampleBundle(16,16).waterDepth());
        assertTrue(region.sampleBundle(16,16).columnWater());
        assertEquals(100,region.height(-1,16));
        assertFalse(region.isColumnWater(-1,16));
    }

    private static CoarseTerrainTile tile(int tileX, int tileZ) {
        var key = new CoarseTerrainTileKey(ResourceLocation.fromNamespaceAndPath("worldadmin","test"),tileX,tileZ,16,32,2);
        int width=key.sampleWidth(), height=key.sampleHeight();
        short[] heights=new short[width*height], ocean=new short[width*height];
        byte[] flags=new byte[width*height];
        for(int z=0;z<height;z++) for(int x=0;x<width;x++) {
            int wx=key.blockXAt(x), wz=key.blockZAt(z), index=z*width+x;
            heights[index]=(short)(1000+wx+2*wz); ocean[index]=(short)(heights[index]-12);
            if(wx==0 && wz==-32) flags[index]=3;
        }
        return new CoarseTerrainTile(key,1000,width,height,heights,ocean,flags,new int[width*height]);
    }
}
