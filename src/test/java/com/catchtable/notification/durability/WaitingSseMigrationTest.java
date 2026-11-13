package com.catchtable.notification.durability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WaitingSseMigrationTest {
    @Autowired DataSource dataSource;
    @Test void freshV38AndUpgradePreserveAllPreviousRowsAndFlywayChecksums() throws Exception {
        for (boolean upgrade : new boolean[]{false,true}) {
            String schema="sse_v38_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=dataSource.getConnection();var sql=connection.createStatement()) {
                sql.execute("CREATE SCHEMA "+schema);
                try {
                    var base=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
                    base.target(upgrade?"37":"38").load().migrate();
                    sql.execute("SET search_path TO "+schema+", public");
                    if(upgrade) {
                        sql.execute(Files.readAllLines(Path.of("scripts/local-seed.sql")).stream().filter(l->!l.startsWith("\\")).collect(Collectors.joining("\n")));
                        seedBusiness(sql);
                        String before=snapshot(sql,schema,37,false);
                        var after=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("38").load();
                        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
                        after.validate();assertThat(snapshot(sql,schema,37,false)).isEqualTo(before);
                    }
                    try(var rs=sql.executeQuery("SELECT count(*) FROM waiting_sse_root")) {rs.next();assertThat(rs.getInt(1)).isZero();}
                    try(var rs=sql.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {rs.next();assertThat(rs.getString(1)).isEqualTo("38");}

                } finally {
                    sql.execute("RESET search_path");sql.execute("DROP SCHEMA "+schema+" CASCADE");
                }
            }
        }
    }
    @Test void freshV39AndV38UpgradePreserveEveryTableAndPreviousChecksum() throws Exception {
        for(boolean upgrade:new boolean[]{false,true}) {
            String schema="sse_v39_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=dataSource.getConnection();var sql=connection.createStatement()) {
                sql.execute("CREATE SCHEMA "+schema);
                try {
                    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                            .target(upgrade?"38":"39").load().migrate();
                    sql.execute("SET search_path TO "+schema+",public");
                    if(upgrade) {
                        sql.execute(Files.readAllLines(Path.of("scripts/local-seed.sql")).stream().filter(l->!l.startsWith("\\")).collect(Collectors.joining("\n")));
                        seedBusiness(sql);
                        sql.execute("""
                                INSERT INTO waiting_sse_root(id,event_key,kind,waiting_id,store_id,message)
                                VALUES('00000000-0000-0000-0000-000000000011','waiting:900001:call:1:direct:test','DIRECT',900001,1,'{"message":"private","eventId":"preserved"}');
                                INSERT INTO waiting_sse_target(id,root_id,member_id,waiting_id,connection_generation,event_type,message,status)
                                VALUES('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000011',1,900001,'prior-generation','WAITING_CALLED','{"message":"private-target","eventId":"preserved"}','UNKNOWN');
                                INSERT INTO waiting_sse_attempt(id,target_id,claim_token,status,reason)
                                VALUES('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000014','UNKNOWN','LEASE_EXPIRED_AFTER_MARK');
                                INSERT INTO waiting_sse_observation(observation_key,root_id,target_id,attempt_id,phase,last_confirmed_stage,observed_at)
                                VALUES('preserved-unknown','00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013','UNKNOWN','ATTEMPT_MARK_COMMITTED',now());
                                UPDATE waiting_sse_capacity SET retained_roots=1;
                                """);
                        String before=snapshot(sql,schema,38,true);
                        var latest=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
                        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
                        assertThat(snapshot(sql,schema,38,true)).isEqualTo(before);
                    }
                    try(var rows=sql.executeQuery("SELECT count(*) FROM information_schema.views WHERE table_schema='"+schema+"' AND table_name IN ('waiting_sse_root_operator','waiting_sse_target_operator')")) {
                        rows.next();assertThat(rows.getInt(1)).isEqualTo(2);
                    }
                    sql.execute("PREPARE sse_v39(bigint,bigint,integer) AS "+Files.readString(Path.of("scripts/waiting-sse-journal.sql")));
                    try(var rows=sql.executeQuery("EXECUTE sse_v39(900001,999,100)")){assertThat(rows.next()).isFalse();}
                    sql.execute("DEALLOCATE sse_v39");sql.execute(Files.readString(Path.of("scripts/waiting-sse-health.sql")));
                } finally {sql.execute("RESET search_path");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
            }
        }
    }
    private void seedBusiness(java.sql.Statement sql) throws Exception {
                        sql.execute("""
                                INSERT INTO waiting(id,member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,call_count,recall_count,created_at,updated_at)
                                VALUES(900001,1,1,'2026-11-11',2,1,1,'CALLED',1,0,now(),now());
                                INSERT INTO waiting_event(event_key,waiting_id,event_type,occurred_at,source_type,source_id,details)
                                VALUES('waiting:900001:call:1',900001,'CALLED',now(),'WAITING','900001','{"preserved":true}');
                                INSERT INTO reservation(id,member_id,store_id,table_id,reservation_start_at,reservation_end_at,party_size,status,requested_at,confirmed_at)
                                OVERRIDING SYSTEM VALUE VALUES(900001,1,1,1,'2030-01-20T12:00:00+09','2030-01-20T14:00:00+09',2,'CONFIRMED',now(),now());
                                INSERT INTO payment(id,member_id,reservation_id,order_id,order_name,amount,status,payment_key,approved_at)
                                OVERRIDING SYSTEM VALUE VALUES(900001,1,900001,'sse-upgrade-order','test',10000,'DONE','sse-test-payment-key',now());
                                INSERT INTO payment_status_history(payment_id,previous_status,status,source,detail,created_at)
                                VALUES(900001,'READY','DONE','SYSTEM','synthetic',now());
                                """);
    }
    private String snapshot(java.sql.Statement sql,String schema,int maxVersion,boolean includeJournal) throws Exception {
        var tables=new java.util.ArrayList<String>();
        try(var r=sql.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='"+schema+"' AND ("+includeJournal+" OR tablename NOT LIKE 'waiting_sse_%') ORDER BY tablename")) {
            while(r.next())tables.add(r.getString(1));
        }
        StringBuilder result=new StringBuilder();
        for(String table:tables) {
            String where=table.equals("flyway_schema_history")?" WHERE version IS NULL OR version::integer<="+maxVersion:"";
            try(var r=sql.executeQuery("SELECT row_to_json(t)::text FROM \""+table+"\" t"+where+" ORDER BY row_to_json(t)::text")) {
                while(r.next())result.append(table).append(':').append(r.getString(1)).append('\n');
            }
        }
        return result.toString();
    }
}
