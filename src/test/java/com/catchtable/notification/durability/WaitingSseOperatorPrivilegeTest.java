package com.catchtable.notification.durability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class WaitingSseOperatorPrivilegeTest {
    @Autowired DataSource dataSource;

    @Test void independentNonOwnerRoleCanInspectTimelineButCannotReadMessagesOrWrite() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="sse_priv_"+suffix, role="sse_reader_"+suffix, password=UUID.randomUUID().toString();
        try(var owner=dataSource.getConnection();var sql=owner.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            sql.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
            try {
                for(String table:new String[]{"waiting","waiting_event","waiting_sse_root","waiting_sse_target","waiting_sse_attempt","waiting_sse_observation","waiting_sse_capacity"})
                    sql.execute("CREATE TABLE "+schema+"."+table+" (LIKE "+owner.getSchema()+"."+table+" INCLUDING ALL)");
                sql.execute("SET search_path TO "+schema+",public");
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V39__add_waiting_sse_operator_views.sql")));
                sql.execute("""
                        INSERT INTO waiting(id,member_id,store_id,waiting_date,party_count,wait_number,queue_order,status,created_at,updated_at)
                        OVERRIDING SYSTEM VALUE VALUES(1,1,1,CURRENT_DATE,2,1,1,'WAITING',now(),now());
                        INSERT INTO waiting_event(id,event_key,waiting_id,event_type,occurred_at,source_type,source_id,details)
                        VALUES(1,'waiting:1:registered',1,'REGISTERED',now(),'WAITING','1','{"private":"secret-business"}');
                        INSERT INTO waiting_sse_capacity VALUES(1,1);
                        INSERT INTO waiting_sse_root(id,event_key,kind,waiting_id,store_id,message,status,expanded_at,expected_recipients,expected_targets)
                        VALUES('00000000-0000-0000-0000-000000000001','waiting:1:registered:direct:WAITING_CHANGED:1:1','DIRECT',1,1,
                          '{"message":"secret-root","eventId":"event-1"}','DONE',now(),1,1);
                        INSERT INTO waiting_sse_target(id,root_id,member_id,waiting_id,connection_generation,event_type,message,status,reason)
                        VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',1,1,'generation-1','WAITING_CHANGED',
                          '{"message":"secret-target","eventId":"event-1"}','SENT','SEND_RETURNED');
                        INSERT INTO waiting_sse_attempt(id,target_id,claim_token,status)
                        VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004','SEND_RETURNED');
                        INSERT INTO waiting_sse_observation(id,observation_key,root_id,target_id,attempt_id,phase,last_confirmed_stage,observed_at)
                        VALUES(1,'result-1','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
                          '00000000-0000-0000-0000-000000000003','SEND_RETURNED','SERVER_OBSERVATION',now());
                        """);
                sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
                // Must match the documented minimum grants; no grants on raw root/target tables.
                sql.execute("GRANT SELECT ON waiting_sse_root_operator,waiting_sse_target_operator TO "+role);
                sql.execute("GRANT SELECT(id,store_id) ON waiting TO "+role);
                sql.execute("GRANT SELECT(id,event_key,event_type) ON waiting_event TO "+role);
                sql.execute("GRANT SELECT(id,target_id,status,marked_at,observed_at,customer_receipt) ON waiting_sse_attempt TO "+role);
                sql.execute("GRANT SELECT(id,root_id,target_id,phase,reason,last_confirmed_stage,recorded_at) ON waiting_sse_observation TO "+role);
                sql.execute("GRANT SELECT(id,retained_roots) ON waiting_sse_capacity TO "+role);
                var credentials=new Properties();credentials.setProperty("user",role);credentials.setProperty("password",password);credentials.setProperty("currentSchema",schema);
                try(Connection reader=DriverManager.getConnection(owner.getMetaData().getURL(),credentials);var read=reader.createStatement()) {
                    assertThat(reader.isReadOnly()).isFalse(); // ACL denial, not a read-only transaction.
                    try(var flags=read.executeQuery("SELECT current_user,rolsuper,rolcreatedb,rolcreaterole,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                        assertThat(flags.next()).isTrue();assertThat(flags.getString(1)).isEqualTo(role);
                        for(int i=2;i<=5;i++)assertThat(flags.getBoolean(i)).isFalse();
                    }
                    try(var flags=read.executeQuery("SELECT has_schema_privilege(current_schema(),'CREATE'),pg_has_role(current_user,'catchtable','MEMBER')")) {
                        flags.next();assertThat(flags.getBoolean(1)).isFalse();assertThat(flags.getBoolean(2)).isFalse();
                    }
                    read.execute("PREPARE timeline(bigint,bigint,integer) AS "+Files.readString(Path.of("scripts/waiting-sse-journal.sql")));
                    try(var rows=read.executeQuery("EXECUTE timeline(1,1,500)")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString("sse_event_id")).isEqualTo("event-1");
                        assertThat(rows.getString("connection_generation")).isEqualTo("generation-1");
                        assertThat(rows.getString("attempt_status")).isEqualTo("SEND_RETURNED");
                        assertThat(rows.getLong("business_event_id")).isEqualTo(1);
                        for(int i=1;i<=rows.getMetaData().getColumnCount();i++)assertThat(String.valueOf(rows.getObject(i))).doesNotContain("secret-");
                        assertThat(rows.next()).isFalse();
                    }
                    try(var rows=read.executeQuery("EXECUTE timeline(1,999,500)")){assertThat(rows.next()).isFalse();}
                    try(var rows=read.executeQuery("EXECUTE timeline(999,1,500)")){assertThat(rows.next()).isFalse();}
                    read.execute(Files.readString(Path.of("scripts/waiting-sse-health.sql")));
                    for(String denied:new String[]{"SELECT message FROM waiting_sse_root","SELECT message FROM waiting_sse_target",
                            "SELECT details FROM waiting_event","UPDATE waiting_sse_root SET status='DONE'",
                            "DELETE FROM waiting_sse_target","UPDATE waiting_sse_root_operator SET status='DONE'",
                            "INSERT INTO waiting_sse_capacity(id) VALUES(1)","DELETE FROM waiting_sse_observation"}) {
                        assertThatThrownBy(()->read.execute(denied)).as(denied).isInstanceOfSatisfying(SQLException.class,
                                failure->assertThat(failure.getSQLState()).isEqualTo("42501"));
                    }
                    System.out.println("Operator ACL: separate LOGIN, no superuser/owner membership/schema CREATE; timeline/health OK; 8 raw-read/write denials SQLSTATE 42501");
                }
            } finally {
                sql.execute("RESET search_path");sql.execute("DROP SCHEMA "+schema+" CASCADE");sql.execute("DROP ROLE "+role);
            }
        }
    }
}
