/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prestosql.tests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.spi.plan.TableScanNode;
import io.prestosql.sql.planner.Plan;
import io.prestosql.sql.planner.optimizations.PlanNodeSearcher;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class AbstractTestStarTreeQueries
        extends AbstractTestQueryFramework
{
    Session sessionStarTree;
    Session sessionNoStarTree;

    protected AbstractTestStarTreeQueries(QueryRunnerSupplier supplier)
    {
        super(supplier);
    }

    @BeforeClass
    public void setUp()
    {
        sessionStarTree = Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "true")
                .build();
        sessionNoStarTree = Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "false")
                .build();
        //Create Empty to force create Metadata catalog and schema. To avoid concurrency issue.
        assertUpdate(sessionNoStarTree, "CREATE CUBE nation_count_all ON nation WITH (AGGREGATIONS=(count(*)), group=())");
        assertUpdate("DROP CUBE nation_count_all");
    }

    @Test
    public void testStarTreeSessionProperty()
    {
        MaterializedResult result = computeActual("SET SESSION enable_star_tree_index = true");
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of("enable_star_tree_index", "true"));
        result = computeActual("SET SESSION enable_star_tree_index = false");
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of("enable_star_tree_index", "false"));
    }

    @Test
    public void testAggregations()
    {
        assertUpdate(sessionNoStarTree, "CREATE CUBE nation_aggregations_cube_1 ON nation " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate(sessionNoStarTree, "INSERT INTO CUBE nation_aggregations_cube_1 where nationkey > -1", 25);
        assertQueryFails(sessionNoStarTree, "INSERT INTO CUBE nation_aggregations_cube_1 where 1 > 0", "Invalid predicate\\. \\(1 > 0\\)");
        assertQuery(sessionStarTree, "SELECT min(regionkey), max(regionkey), sum(regionkey) from nation group by nationkey");
        assertQuery(sessionStarTree, "SELECT COUNT(distinct nationkey), count(distinct regionkey) from nation");
        assertQuery(sessionStarTree, "SELECT COUNT(distinct nationkey), count(distinct regionkey) from nation group by nationkey");
        assertQuery(sessionStarTree, "SELECT avg(nationkey) from nation group by nationkey");
        assertUpdate("DROP CUBE nation_aggregations_cube_1");
    }

    @Test
    public void testShowCubes()
    {
        computeActual("CREATE TABLE nation_show_cube_table_1 AS SELECT * FROM nation");
        assertUpdate(sessionNoStarTree, "CREATE CUBE nation_show_cube_1 ON nation_show_cube_table_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate(sessionNoStarTree, "CREATE CUBE nation_show_cube_2 ON nation_show_cube_table_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=())");
        MaterializedResult result = computeActual("SHOW CUBES FOR nation_show_cube_table_1");
        MaterializedRow matchingRow1 = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_show_cube_1")).findFirst().orElse(null);
        assertNotNull(matchingRow1);
        assertTrue(matchingRow1.getFields().containsAll(ImmutableList.of("hive.tpch.nation_show_cube_1", "hive.tpch.nation_show_cube_table_1", "Inactive", "nationkey")));

        MaterializedRow matchingRow2 = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_show_cube_2")).findFirst().orElse(null);
        assertNotNull(matchingRow2);
        assertTrue(matchingRow2.getFields().containsAll(ImmutableList.of("hive.tpch.nation_show_cube_2", "hive.tpch.nation_show_cube_table_1", "Inactive", "")));

        result = computeActual("SHOW CUBES FOR nation_show_cube_table_1");
        assertEquals(result.getRowCount(), 2);

        matchingRow1 = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_show_cube_1")).findFirst().orElse(null);
        assertNotNull(matchingRow1);
        assertTrue(result.getMaterializedRows().get(0).getFields().containsAll(ImmutableList.of("hive.tpch.nation_show_cube_1", "hive.tpch.nation_show_cube_table_1", "Inactive", "nationkey")));

        matchingRow2 = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_show_cube_2")).findFirst().orElse(null);
        assertNotNull(matchingRow2);
        assertTrue(result.getMaterializedRows().get(1).getFields().containsAll(ImmutableList.of("hive.tpch.nation_show_cube_2", "hive.tpch.nation_show_cube_table_1", "Inactive", "")));
        assertUpdate("DROP CUBE nation_show_cube_1");
        assertUpdate("DROP CUBE nation_show_cube_2");
        assertUpdate("DROP TABLE nation_show_cube_table_1");
    }

    @Test
    public void testInsertIntoCube()
    {
        computeActual("CREATE TABLE nation_table_cube_insert_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_insert_cube_1 ON nation_table_cube_insert_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate("INSERT INTO CUBE nation_insert_cube_1 where nationkey > 5", 19);
        assertQueryFails("INSERT INTO CUBE nation where 1 > 0", "Cube not found 'hive.tpch.nation'");
        assertQueryFails("INSERT INTO CUBE nation_insert_cube_1 where regionkey > 5", "All columns in where clause must be part Cube group\\.");
        assertUpdate("DROP CUBE nation_insert_cube_1");
        assertUpdate("DROP TABLE nation_table_cube_insert_test_1");
    }

    @Test
    public void testInsertIntoCubeWithoutPredicate()
    {
        computeActual("CREATE TABLE nation_table_cube_insert_2 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_insert_2 ON nation_table_cube_insert_2 WITH (AGGREGATIONS=(count(*)), group=(name))");
        assertQuerySucceeds("INSERT INTO CUBE nation_cube_insert_2");
        assertQuery(sessionNoStarTree,
                "SELECT count(*) FROM nation_table_cube_insert_2 WHERE name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name",
                assertTableScan("nation_table_cube_insert_2"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_cube_insert_2 WHERE name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name",
                assertTableScan("nation_cube_insert_2"));
        assertUpdate("DROP CUBE nation_cube_insert_2");
        assertUpdate("DROP TABLE nation_table_cube_insert_2");
    }

    @Test
    public void testInsertOverwriteCube()
    {
        computeActual("CREATE TABLE nation_table_cube_insert_overwrite_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_insert_overwrite_cube_1 ON nation_table_cube_insert_overwrite_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate("INSERT INTO CUBE nation_insert_overwrite_cube_1 where nationkey > 5", 19);
        assertEquals(computeScalar("SELECT COUNT(*) FROM nation_insert_overwrite_cube_1"), 19L);
        assertUpdate("INSERT OVERWRITE CUBE nation_insert_overwrite_cube_1 where nationkey > 5", 19);
        assertEquals(computeScalar("SELECT COUNT(*) FROM nation_insert_overwrite_cube_1"), 19L);
        assertUpdate("DROP CUBE nation_insert_overwrite_cube_1");
        assertUpdate("DROP TABLE nation_table_cube_insert_overwrite_test_1");
    }

    @Test
    public void testCountAggregation()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_count_agg_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_count_agg_cube_1 ON nation_table_count_agg_1 WITH (AGGREGATIONS=(count(*)), group=(name))");
        assertQuerySucceeds("INSERT INTO CUBE nation_count_agg_cube_1 where name = 'CHINA'");
        assertQuery(sessionNoStarTree,
                "SELECT count(*) FROM nation_table_count_agg_1 WHERE name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name",
                assertTableScan("nation_table_count_agg_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_count_agg_1 WHERE name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name",
                assertTableScan("nation_count_agg_cube_1"));
        assertQuerySucceeds("INSERT INTO CUBE nation_count_agg_cube_1 where name = 'CANADA'");
        assertQuery(sessionNoStarTree,
                "SELECT count(*) FROM nation_table_count_agg_1 WHERE name = 'CANADA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CANADA' GROUP BY name",
                assertTableScan("nation_table_count_agg_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_count_agg_1 WHERE name = 'CANADA' OR name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CANADA' OR name = 'CHINA' GROUP BY name",
                assertTableScan("nation_count_agg_cube_1"));
        assertUpdate("DROP CUBE nation_count_agg_cube_1");
        assertUpdate("DROP TABLE nation_table_count_agg_1");
    }

    @Test
    public void testMultiColumnGroup()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_multi_column_group AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_multi_column_group ON nation_table_multi_column_group WITH (AGGREGATIONS=(count(*)), group=(name, regionkey))");
        assertQuerySucceeds("INSERT INTO CUBE nation_cube_multi_column_group where name = 'CHINA'");
        assertQuery(sessionNoStarTree,
                "SELECT count(*) FROM nation_table_multi_column_group WHERE name = 'CHINA' GROUP BY name, regionkey",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name, regionkey",
                assertTableScan("nation_table_multi_column_group"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_multi_column_group WHERE name = 'CHINA' GROUP BY name",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name",
                assertTableScan("nation_table_multi_column_group"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_multi_column_group WHERE name = 'CHINA' GROUP BY name, regionkey",
                "SELECT count(*) FROM nation WHERE name = 'CHINA' GROUP BY name, regionkey",
                assertTableScan("nation_cube_multi_column_group"));
        assertUpdate("DROP CUBE nation_cube_multi_column_group");
        assertUpdate("DROP TABLE nation_table_multi_column_group");
    }

    @Test
    public void testDuplicateDataInsert()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_duplicate_insert_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_duplicate_insert_1 ON nation_table_duplicate_insert_1 WITH (AGGREGATIONS=(count(*)), group=(name))");
        assertQuerySucceeds("INSERT INTO CUBE nation_cube_duplicate_insert_1 where name = 'CHINA'");
        assertQueryFails("INSERT INTO CUBE nation_cube_duplicate_insert_1 where name = 'CHINA'", "Cannot allow insert. Cube already contains data for the given predicate.*");
        assertUpdate("DROP CUBE nation_cube_duplicate_insert_1");
        assertUpdate("DROP TABLE nation_table_duplicate_insert_1");
    }

    @Test
    public void testDuplicateInsertCubeWithAllData()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_duplicate_insert_2 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_duplicate_insert_2 ON nation_table_duplicate_insert_2 WITH (AGGREGATIONS=(count(*)), group=(name))");
        assertQuerySucceeds("INSERT INTO CUBE nation_cube_duplicate_insert_2 where name = 'CHINA'");
        assertQueryFails("INSERT INTO CUBE nation_cube_duplicate_insert_2", "Cannot allow insert. Inserting entire dataset but cube already has partial data*");
        assertUpdate("DROP CUBE nation_cube_duplicate_insert_2");
        assertUpdate("DROP TABLE nation_table_duplicate_insert_2");
    }

    @Test
    public void testMultipleInsertIntoCube()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_multi_insert_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_multi_insert_cube_1 ON nation_table_multi_insert_1 WITH (AGGREGATIONS=(count(*)), group=(name))");
        assertQuerySucceeds("INSERT INTO CUBE nation_multi_insert_cube_1 where name = 'CHINA'");
        assertQuerySucceeds("INSERT INTO CUBE nation_multi_insert_cube_1 where name = 'CANADA'");
        assertUpdate("DROP CUBE nation_multi_insert_cube_1");
        assertUpdate("DROP TABLE nation_table_multi_insert_1");
    }

    @Test
    public void testCreateCube()
    {
        computeActual("CREATE TABLE nation_table_create_cube_test_1 AS SELECT * FROM nation");
        assertQueryFails("CREATE CUBE nation ON nation " +
                "WITH (AGGREGATIONS=(count(*))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])", "line 1:1: Table 'hive.tpch.nation' already exists");
        assertQueryFails("CREATE CUBE nation_create_cube_1 ON abcd " +
                "WITH (AGGREGATIONS=(count(*), count(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])", "line 1:1: Table 'hive.tpch.abcd' does not exist");
        assertQueryFails("CREATE CUBE nation_create_cube_1 ON nation " +
                "WITH (AGGREGATIONS=(sum(distinct nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])", "line 1:1: Distinct is currently only supported for count");
        assertUpdate("CREATE CUBE nation_create_cube_1 ON nation_table_create_cube_test_1 " +
                "WITH (AGGREGATIONS=(count(*))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertQueryFails("CREATE CUBE nation_create_cube_1 ON nation_table_create_cube_test_1 " +
                "WITH (AGGREGATIONS=(count(*), count(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])", "line 1:1: Cube 'hive.tpch.nation_create_cube_1' already exists");
        assertUpdate("DROP CUBE nation_create_cube_1");
        assertUpdate("DROP TABLE nation_table_create_cube_test_1");
    }

    @Test
    public void testCreateCubeTransactional()
    {
        computeActual("CREATE TABLE nation_table_cube_create_transactional_test_1 AS SELECT * FROM nation");
        assertQueryFails("CREATE CUBE nation_create_transactional_cube_1 ON nation_table_cube_create_transactional_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), transactional=true)", "line 1:1: nation_create_transactional_cube_1 is a star-tree cube with transactional = true is not supported");

        assertQueryFails("CREATE CUBE nation_create_transactional_cube_2 ON nation_table_cube_create_transactional_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'], transactional=true)", "line 1:1: nation_create_transactional_cube_2 is a star-tree cube with transactional = true is not supported");

        assertUpdate("DROP TABLE nation_table_cube_create_transactional_test_1");
    }

    @Test
    public void testDeleteFromCube()
    {
        computeActual("CREATE TABLE nation_table_delete_from_cube_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_delete_from_cube_1 ON nation_table_delete_from_cube_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate("INSERT INTO CUBE nation_delete_from_cube_1 where nationkey > 5", 19);
        assertQueryFails("DELETE FROM nation_delete_from_cube_1 where nationkey > 5", "line 1:1: hive.tpch.nation_delete_from_cube_1 is a star-tree cube, DELETE is not supported");

        assertUpdate("DROP CUBE nation_delete_from_cube_1");
        assertUpdate("DROP TABLE nation_table_delete_from_cube_test_1");
    }

    @Test
    public void testUpdateCube()
    {
        computeActual("CREATE TABLE nation_table_update_cube_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_update_cube_1 ON nation_table_update_cube_test_1 " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        assertUpdate("INSERT INTO CUBE nation_update_cube_1 where nationkey > 5", 19);
        assertQueryFails("UPDATE nation_update_cube_1 set regionkey = 10 where nationkey > 5", "line 1:1: hive.tpch.nation_update_cube_1 is a star-tree cube, UPDATE is not supported");

        assertUpdate("DROP CUBE nation_update_cube_1");
        assertUpdate("DROP TABLE nation_table_update_cube_test_1");
    }

    @Test
    public void testCubeStatusChange()
    {
        computeActual("CREATE TABLE nation_table_status_test AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_status_cube_1 ON nation_table_status_test " +
                "WITH (AGGREGATIONS=(count(*), COUNT(distinct nationkey), count(distinct regionkey), avg(nationkey), count(regionkey), sum(regionkey)," +
                " min(regionkey), max(regionkey), max(nationkey), min(nationkey))," +
                " group=(nationkey), format= 'orc', partitioned_by = ARRAY['nationkey'])");
        MaterializedResult result = computeActual("SHOW CUBES FOR nation_table_status_test");
        MaterializedRow matchingRow = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_status_cube_1")).findFirst().orElse(null);
        assertNotNull(matchingRow);
        assertEquals(matchingRow.getField(2), "Inactive");

        assertUpdate("INSERT INTO CUBE nation_status_cube_1 where nationkey > 5", 19);
        result = computeActual("SHOW CUBES FOR nation_table_status_test");
        matchingRow = result.getMaterializedRows().stream().filter(row -> row.getField(0).toString().contains("nation_status_cube_1")).findFirst().orElse(null);
        assertNotNull(matchingRow);
        assertEquals(matchingRow.getField(2), "Active");

        assertUpdate("DROP CUBE nation_status_cube_1");
        assertUpdate("DROP TABLE nation_table_status_test");
    }

    @Test
    public void testEmptyGroup()
    {
        assertQuerySucceeds("CREATE TABLE nation_table_empty_group_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_empty_group_test_1 ON nation_table_empty_group_test_1 WITH (aggregations=(count(*)), group=())");
        assertQuerySucceeds("INSERT INTO CUBE nation_cube_empty_group_test_1");
        Object rowCount = computeScalar("SELECT count(*) FROM nation_cube_empty_group_test_1");
        assertEquals(rowCount, 1L);
        assertQuery(sessionNoStarTree,
                "SELECT count(*) FROM nation_table_empty_group_test_1",
                "SELECT count(*) FROM nation",
                assertTableScan("nation_table_empty_group_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_empty_group_test_1",
                "SELECT count(*) FROM nation",
                assertTableScan("nation_cube_empty_group_test_1"));
        assertUpdate("DROP CUBE nation_cube_empty_group_test_1");
        assertUpdate("DROP TABLE nation_table_empty_group_test_1");
    }

    @Test
    public void testCreateCubeSyntax()
    {
        assertQueryFails("CREATE CUBE cube_syntax_test_1 ON nation WITH ()", "Missing property: GROUP");
        assertQueryFails("CREATE CUBE cube_syntax_test_2 ON nation WITH (AGGREGATIONS = (count(*), sum(nation_key)))", "Missing property: GROUP");
        assertQueryFails("CREATE CUBE cube_syntax_test_3 ON nation WITH (GROUP=(name))", "Missing property: AGGREGATIONS");
        assertQueryFails("CREATE CUBE cube_syntax_test_4 ON nation WITH (format = 'ORC', partitioned_by = ARRAY['region_key'], GROUP=(name))", "Missing property: AGGREGATIONS");
        assertQueryFails("CREATE CUBE cube_syntax_test_5 ON nation WITH (AGGREGATIONS = (count(*), sum(nation_key)), GROUP = (name), AGGREGATIONS = (sum(region_key)))", "Duplicate property: AGGREGATIONS");
        assertQueryFails("CREATE CUBE cube_syntax_test_6 ON nation WITH (GROUP = (country), GROUP = (name), AGGREGATIONS = (sum(region_key)))", "Duplicate property: GROUP");
    }

    @Test
    public void testInsertWithDifferentSecondInsertPredicates()
    {
        assertUpdate("CREATE CUBE partial_inserts_test_1 ON nation WITH (AGGREGATIONS= (count(*)), GROUP=(regionkey, nationkey))");
        assertUpdate("INSERT INTO CUBE partial_inserts_test_1 WHERE nationkey = 1", 1);
        assertQueryFails("INSERT INTO CUBE partial_inserts_test_1 WHERE regionkey = 1", "Where condition must only use the columns from the first insert: nationkey.");
        assertQueryFails("INSERT INTO CUBE partial_inserts_test_1 WHERE nationkey > 1 and regionkey = 1", "Where condition must only use the columns from the first insert: nationkey.");
        assertUpdate("INSERT INTO CUBE partial_inserts_test_1 WHERE nationkey = 2", 1);

        assertUpdate("CREATE CUBE partial_inserts_test_2 ON nation WITH (AGGREGATIONS= (count(*)), GROUP=(regionkey, nationkey))");
        assertUpdate("INSERT INTO CUBE partial_inserts_test_2 WHERE nationkey = 1 and regionkey = 1", 1);
        assertQueryFails("INSERT INTO CUBE partial_inserts_test_2 WHERE regionkey = 2", "Where condition must only use the columns from the first insert: nationkey, regionkey.");
        assertQueryFails("INSERT INTO CUBE partial_inserts_test_2", "Cannot allow insert. Inserting entire dataset but cube already has partial data");

        assertUpdate("DROP CUBE partial_inserts_test_1");
        assertUpdate("DROP CUBE partial_inserts_test_2");
    }

    @Test
    public void testAggregationsWithPartialData()
    {
        computeActual("CREATE TABLE nation_table_partial_data_test_1 AS SELECT * FROM nation");
        assertUpdate("CREATE CUBE nation_cube_partial_data_1 ON nation_table_partial_data_test_1 WITH (AGGREGATIONS=(count(*)), GROUP=(nationkey))");
        assertUpdate("INSERT INTO CUBE nation_cube_partial_data_1 WHERE nationkey = 1", 1);
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1",
                "SELECT count(*) FROM nation WHERE nationkey > 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey = 1",
                "SELECT count(*) FROM nation WHERE nationkey = 1",
                assertTableScan("nation_cube_partial_data_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey >= 1",
                "SELECT count(*) FROM nation WHERE nationkey >= 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertUpdate("DROP CUBE nation_cube_partial_data_1");
        assertUpdate("CREATE CUBE nation_cube_partial_data_2 ON nation_table_partial_data_test_1 WITH (AGGREGATIONS=(count(*)), GROUP=(nationkey, regionkey))");
        assertUpdate("INSERT INTO CUBE nation_cube_partial_data_2 WHERE nationkey = 1 and regionkey = 1", 1);
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1",
                "SELECT count(*) FROM nation WHERE nationkey > 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey = 1",
                "SELECT count(*) FROM nation WHERE nationkey = 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey >= 1",
                "SELECT count(*) FROM nation WHERE nationkey >= 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey = 1 and regionkey = 1",
                "SELECT count(*) FROM nation WHERE nationkey = 1 and regionkey = 1",
                assertTableScan("nation_cube_partial_data_2"));
        assertUpdate("INSERT INTO CUBE nation_cube_partial_data_2 WHERE nationkey > 1 and regionkey = 2", 5);
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1",
                "SELECT count(*) FROM nation WHERE nationkey > 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey = 1 and regionkey = 1",
                "SELECT count(*) FROM nation WHERE nationkey = 1 and regionkey = 1",
                assertTableScan("nation_cube_partial_data_2"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1 and regionkey = 2",
                "SELECT count(*) FROM nation WHERE nationkey > 1 and regionkey = 2",
                assertTableScan("nation_cube_partial_data_2"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1 and regionkey > 1",
                "SELECT count(*) FROM nation WHERE nationkey > 1 and regionkey > 1",
                assertTableScan("nation_table_partial_data_test_1"));
        assertQuery(sessionStarTree,
                "SELECT count(*) FROM nation_table_partial_data_test_1 WHERE nationkey > 1 and regionkey = 1",
                "SELECT count(*) FROM nation WHERE nationkey > 1 and regionkey = 1",
                assertTableScan("nation_table_partial_data_test_1"));

        assertUpdate("DROP CUBE nation_cube_partial_data_2");
        assertUpdate("DROP TABLE nation_table_partial_data_test_1");
    }

    @Test
    public void testCubeMustNotBeUsed()
    {
        computeActual("CREATE TABLE line_item_table_test_1 AS SELECT * FROM lineitem");
        assertUpdate("CREATE CUBE line_item_cube_test_1 ON line_item_table_test_1 WITH (AGGREGATIONS=(sum(extendedprice)), GROUP=(orderkey))");
        assertQuerySucceeds("INSERT INTO CUBE line_item_cube_test_1");
        assertQuery(sessionStarTree,
                "SELECT custkey FROM orders o WHERE 100000 < (SELECT sum(extendedprice) FROM line_item_table_test_1 l WHERE l.orderkey = o.orderkey) ORDER BY custkey LIMIT 10",
                "SELECT custkey FROM orders o WHERE 100000 < (SELECT sum(extendedprice) FROM lineitem l WHERE l.orderkey = o.orderkey) ORDER BY custkey LIMIT 10",
                assertInTableScans("line_item_table_test_1"));
        assertUpdate("DROP CUBE line_item_cube_test_1");
        assertUpdate("DROP TABLE line_item_table_test_1");
    }

    @Test
    public void testOtherQueryTypes()
    {
        List<Session> sessions = ImmutableList.of(
                Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "true")
                .setSystemProperty(SystemSessionProperties.ENABLE_EXECUTION_PLAN_CACHE, "true")
                .build(),
                Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "false")
                .setSystemProperty(SystemSessionProperties.ENABLE_EXECUTION_PLAN_CACHE, "true")
                .build(),
                Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "true")
                .setSystemProperty(SystemSessionProperties.ENABLE_EXECUTION_PLAN_CACHE, "false")
                .build(),
                Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.ENABLE_STAR_TREE_INDEX, "false")
                .setSystemProperty(SystemSessionProperties.ENABLE_EXECUTION_PLAN_CACHE, "false")
                .build());
        for (Session session : sessions) {
            assertQuery(session,
                    "WITH temp_table as(SELECT nationkey, count(*) AS count FROM nation WHERE nationkey > 10 GROUP BY nationkey) SELECT nationkey, count FROM temp_table");
            assertQuery(session,
                    "SELECT o.orderpriority, COUNT(*) FROM orders o WHERE o.orderdate >= date '1993-07-01' AND EXISTS (SELECT * FROM lineitem l WHERE l.orderkey = o.orderkey AND (l.returnflag = 'R' OR l.receiptdate > l.commitdate)) GROUP BY o.orderpriority");
            assertQuerySucceeds(session,
                    "create view count_by_shipmode_cube_test_1 as select shipmode, count(*) as count from lineitem group by shipmode");
            assertQuerySucceeds(session,
                    "DROP VIEW count_by_shipmode_cube_test_1");
            assertQuerySucceeds(session,
                    "select sum(l.extendedprice) / 7.0 as avg_yearly from lineitem l, part p where p.partkey = l.partkey and p.brand = 'Brand#33' and p.container = 'WRAP PACK' and l.quantity < (select 0.2 * avg(l2.quantity) from lineitem l2 where l2.partkey = p.partkey)");
        }
    }

    private Consumer<Plan> assertInTableScans(String tableName)
    {
        return plan ->
        {
            boolean matchFound = PlanNodeSearcher.searchFrom(plan.getRoot())
                    .where(TableScanNode.class::isInstance)
                    .findAll()
                    .stream()
                    .map(TableScanNode.class::cast)
                    .anyMatch(node -> node.getTable().getFullyQualifiedName().endsWith(tableName));

            if (!matchFound) {
                fail("Table " + tableName + " was not used for scan");
            }
        };
    }

    private Consumer<Plan> assertTableScan(String tableName)
    {
        return plan ->
        {
            Optional<TableScanNode> tableScanNode = PlanNodeSearcher.searchFrom(plan.getRoot())
                    .where(TableScanNode.class::isInstance)
                    .findSingle()
                    .map(TableScanNode.class::cast);
            if (!tableScanNode.isPresent() || !tableScanNode.get().getTable().getFullyQualifiedName().endsWith(tableName)) {
                fail("Table " + tableName + " was not used for scan");
            }
        };
    }
}
