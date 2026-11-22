package com.example.rbmdemo;

import org.roaringbitmap.RoaringBitmap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.io.IOException;

/**
 * RoaringBitmap详细性能分析类
 * 将结果保存到CSV文件中以便进一步分析
 */
public class PerfAnalysis {
    
    private static final int BITMAP_COUNT = 1000;
    private static final int MAX_VALUE = 1000000;
    // bitmap塞多少个数字，由于可能有重复值，实际塞入的数会比这个少
    private static final int TARGET_CARDINALITY = 700000; // 每个bitmap的目标基数
    private static final Random RANDOM = new Random(42);
    
    public static void main(String[] args) {
        System.out.println("=== RoaringBitmap 1000个bitmap AND操作详细性能分析 ===");
        System.out.println("每个bitmap目标基数: " + TARGET_CARDINALITY + "\n");
        
        // 创建表格头部
        System.out.println("+---------------------------------------------------------------+");
        System.out.println("| 场景名称            | 平均耗时   | 最小耗时   | 最大耗时   | 结果基数    | 有值Bitmap数  | 平均基数   |");
        System.out.println("+---------------------------------------------------------------+");
        
        // 测试各种场景并收集结果
        List<ReportUtils.PerformanceResult> results = new ArrayList<>();
        results.add(testScenario("多数bitmap有值        ", createMostPopulatedBitmaps()));
        results.add(testScenario("多数bitmap为空        ", createMostEmptyBitmaps()));
        results.add(testScenario("混合情况            ", createMixedBitmaps()));
        results.add(testScenario("所有相同数据        ", createAllSameDataBitmaps()));
        results.add(testScenario("所有bitmap都为空     ", createAllEmptyBitmaps()));
        results.add(testScenario("所有bitmap都包含所有值", createAllFullBitmaps()));
        results.add(testScenario("范围不同           ", createAllPopulatedDifferentRanges()));
        
        System.out.println("+---------------------------------------------------------------+");
        
        // 保存结果到CSV文件并生成Markdown报告
        try {
            ReportUtils.saveResultsToCsv(results, "results.csv");
            ReportUtils.generateMarkdownReportFromData(results, "report.md");
            System.out.println("Markdown性能报告已生成: report.md");
        } catch (IOException e) {
            System.err.println("保存文件时出错: " + e.getMessage());
        }
        
        System.out.println("=== 性能分析完成 ===");
    }
    
    private static List<RoaringBitmap> createMostPopulatedBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            for (int j = 0; j < TARGET_CARDINALITY; j++) {
                bitmap.add(RANDOM.nextInt(MAX_VALUE));
            }
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createMostEmptyBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            if (i < BITMAP_COUNT * 0.1) {
                for (int j = 0; j < TARGET_CARDINALITY; j++) {
                    bitmap.add(RANDOM.nextInt(MAX_VALUE));
                }
            }
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createMixedBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            if (i < BITMAP_COUNT * 0.33) {
                for (int j = 0; j < TARGET_CARDINALITY*0.1; j++) {
                    bitmap.add(RANDOM.nextInt(MAX_VALUE));
                }
            } else if (i < BITMAP_COUNT * 0.66) {
                for (int j = 0; j < TARGET_CARDINALITY; j++) {
                    bitmap.add(RANDOM.nextInt(MAX_VALUE));
                }
            }
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createAllSameDataBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        int[] commonData = new int[TARGET_CARDINALITY];
        for (int i = 0; i < TARGET_CARDINALITY; i++) {
            commonData[i] = RANDOM.nextInt(MAX_VALUE);
        }
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            for (int value : commonData) {
                bitmap.add(value);
            }
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createAllEmptyBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            bitmaps.add(new RoaringBitmap());
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createAllFullBitmaps() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            bitmap.add(0L, MAX_VALUE);
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static List<RoaringBitmap> createAllPopulatedDifferentRanges() {
        List<RoaringBitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < BITMAP_COUNT; i++) {
            RoaringBitmap bitmap = new RoaringBitmap();
            int rangeStart = i * (MAX_VALUE / BITMAP_COUNT);
            int rangeEnd = rangeStart + TARGET_CARDINALITY;
            for (int j = rangeStart; j < rangeEnd; j++) {
                if (j < MAX_VALUE) {
                    bitmap.add(j);
                }
            }
            bitmaps.add(bitmap);
        }
        return bitmaps;
    }
    
    private static ReportUtils.PerformanceResult testScenario(String scenarioName, List<RoaringBitmap> bitmaps) {
        // 统计信息
        int populatedCount = 0;
        long totalCardinality = 0;
        long minCardinality = Long.MAX_VALUE;
        long maxCardinality = Long.MIN_VALUE;
        
        for (RoaringBitmap bitmap : bitmaps) {
            long cardinality = bitmap.getLongCardinality();
            if (cardinality > 0) {
                populatedCount++;
                totalCardinality += cardinality;
                minCardinality = Math.min(minCardinality, cardinality);
                maxCardinality = Math.max(maxCardinality, cardinality);
            }
        }
        // 有值的bitmap中，平均元素的个数
        long avgCardinality = populatedCount > 0 ? totalCardinality / populatedCount : 0;
        
        // 预热
        for (int i = 0; i < 2; i++) {
            RoaringBitmap result = new RoaringBitmap();
            result.add(0, MAX_VALUE);
            for (RoaringBitmap bitmap : bitmaps) {
                result.and(bitmap);
            }
        }
        
        // 性能测试
        int runs = 10;
        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        long resultCardinality = 0;
        
        for (int i = 0; i < runs; i++) {
            long startTime = System.nanoTime();
            
            RoaringBitmap result = new RoaringBitmap();
            result.add(0, MAX_VALUE);
            for (RoaringBitmap bitmap : bitmaps) {
                result.and(bitmap);
            }
            
            long endTime = System.nanoTime();
            long duration = endTime - startTime;
            
            totalTime += duration;
            minTime = Math.min(minTime, duration);
            maxTime = Math.max(maxTime, duration);
            
            if (i == 0) {
                resultCardinality = result.getLongCardinality();
            }
        }
        
        long avgTime = totalTime / runs;
        
        // 转换为微秒
        double avgTimeMicros = avgTime / 1000.0;
        double minTimeMicros = minTime / 1000.0;
        double maxTimeMicros = maxTime / 1000.0;
        
        // 以表格形式输出结果
        System.out.printf("| %s | %8.2f μs | %8.2f μs | %8.2f μs | %9d | %6d/%-4d | %8d |\n",
                scenarioName,
                avgTimeMicros,
                minTimeMicros,
                maxTimeMicros,
                resultCardinality,
                populatedCount,
                bitmaps.size(),
                avgCardinality);
        
        // 返回性能结果对象
        return new ReportUtils.PerformanceResult(
                scenarioName.trim(),
                avgTimeMicros,
                minTimeMicros,
                maxTimeMicros,
                resultCardinality,
                populatedCount,
                bitmaps.size(),
                avgCardinality);
    }
}