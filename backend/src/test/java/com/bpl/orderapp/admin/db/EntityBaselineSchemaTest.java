package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * With ddl-auto=none nothing else checks that entities and the Flyway
 * baseline agree. For every entity: each mapped column must exist in the
 * table, and every NOT NULL column without a DB default must be mapped
 * (otherwise inserts fail at runtime with "NOT NULL constraint failed").
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EntityBaselineSchemaTest {

    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @Test
    void entitiesMatchBaselineSchema() {
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        List<String> problems = new ArrayList<>();
        int[] entities = {0};

        sf.getMappingMetamodel().forEachEntityDescriptor(p -> {
            entities[0]++;
            AbstractEntityPersister ep = (AbstractEntityPersister) p;
            String table = ep.getTableName();

            Set<String> mapped = new HashSet<>();
            for (String c : ep.getIdentifierColumnNames()) {
                mapped.add(c.toLowerCase(Locale.ROOT));
            }
            for (String prop : ep.getPropertyNames()) {
                for (String c : ep.getPropertyColumnNames(prop)) {
                    if (c != null) {
                        mapped.add(c.toLowerCase(Locale.ROOT));
                    }
                }
            }

            List<Map<String, Object>> info = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            if (info.isEmpty()) {
                problems.add("table missing in baseline: " + table);
                return;
            }
            Set<String> dbCols = new HashSet<>();
            for (Map<String, Object> col : info) {
                String name = ((String) col.get("name")).toLowerCase(Locale.ROOT);
                dbCols.add(name);
                boolean notNull = ((Number) col.get("notnull")).intValue() == 1;
                boolean hasDefault = col.get("dflt_value") != null;
                boolean isPk = ((Number) col.get("pk")).intValue() > 0;
                if (notNull && !hasDefault && !isPk && !mapped.contains(name)) {
                    problems.add(table + "." + name + " is NOT NULL with no default but no entity maps it");
                }
            }
            for (String c : mapped) {
                if (!dbCols.contains(c)) {
                    problems.add(table + "." + c + " is mapped by an entity but missing in baseline");
                }
            }
        });

        assertTrue(entities[0] >= 15, "expected at least 15 entities, found " + entities[0]);
        assertTrue(problems.isEmpty(), "entity/baseline mismatches:\n" + String.join("\n", problems));
    }
}
