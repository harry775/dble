package com.actiontech.dble.manager.response;

import com.actiontech.dble.backend.mysql.PacketUtil;
import com.actiontech.dble.config.*;
import com.actiontech.dble.config.model.UserConfig;
import com.actiontech.dble.manager.ManagerConnection;
import com.actiontech.dble.net.mysql.*;
import com.actiontech.dble.server.variables.SystemVariables;
import com.actiontech.dble.server.variables.VarsExtractorHandler;
import com.actiontech.dble.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Created by szf on 2018/8/6.
 */
public final class DryRun {
    private static final Logger LOGGER = LoggerFactory.getLogger(DryRun.class);
    private static final int FIELD_COUNT = 3;
    private static final ResultSetHeaderPacket HEADER = PacketUtil.getHeader(FIELD_COUNT);
    private static final FieldPacket[] FIELDS = new FieldPacket[FIELD_COUNT];
    private static final EOFPacket EOF = new EOFPacket();

    static {
        int i = 0;
        byte packetId = 0;
        HEADER.setPacketId(++packetId);

        FIELDS[i] = PacketUtil.getField("TYPE",
                Fields.FIELD_TYPE_VAR_STRING);
        FIELDS[i++].setPacketId(++packetId);

        FIELDS[i] = PacketUtil.getField("LEVEL", Fields.FIELD_TYPE_VAR_STRING);
        FIELDS[i++].setPacketId(++packetId);

        FIELDS[i] = PacketUtil.getField("DETAIL", Fields.FIELD_TYPE_VAR_STRING);
        FIELDS[i++].setPacketId(++packetId);

        EOF.setPacketId(++packetId);
    }


    private DryRun() {
    }

    public static void execute(ManagerConnection c, String stmt) {


        LOGGER.info("reload config(dry-run): load all xml info start");
        ConfigInitializer loader;
        try {
            loader = new ConfigInitializer(true, false);
        } catch (Exception e) {
            c.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, e.getMessage());
            return;
        }

        //check all the config is legal
        //loader.check();

        List<ErrorInfo> list = new ArrayList<>();
        try {
            loader.testConnection(false);
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("just test ,not stop reload, catch exception", e);
            }
        }

        list.addAll(loader.getErrorInfos());

        ServerConfig serverConfig = new ServerConfig(loader);
        SystemVariables newSystemVariables = null;
        VarsExtractorHandler handler = new VarsExtractorHandler(loader.getDataHosts());
        newSystemVariables = handler.execute();
        if (newSystemVariables == null) {
            if (!loader.isDataHostWithoutWH()) {
                list.add(new ErrorInfo("BACKEND", "ERROR", "Get Vars from backend failed,Maybe all backend Mysql can't connected"));
            }
        } else {
            try {

                if (newSystemVariables.isLowerCaseTableNames()) {
                    serverConfig.reviseLowerCase();
                } else {
                    serverConfig.selfChecking0();
                }
            } catch (Exception e) {
                list.add(new ErrorInfo("Xml", "ERROR", e.getMessage()));
            }
        }


        Map<String, UserConfig> userMap = serverConfig.getUsers();
        if (userMap != null && userMap.size() > 0) {
            Set<String> schema = new HashSet<String>();
            boolean hasManagerUser = false;
            boolean hasServerUser = false;
            for (UserConfig user : userMap.values()) {
                if (user.isManager()) {
                    hasManagerUser = true;
                    continue;
                }
                hasServerUser = true;
                schema.addAll(user.getSchemas());
            }

            if (!hasServerUser) {
                list.add(new ErrorInfo("XML", "ERROR", "There is No Server User"));
            } else if (schema.size() <= serverConfig.getSchemas().size()) {
                for (String schemaName : serverConfig.getSchemas().keySet()) {
                    if (!schema.contains(schemaName)) {
                        list.add(new ErrorInfo("XML", "WARNING", "Schema:" + schemaName + " has no user"));
                    }
                }
            }

            if (!hasManagerUser) {
                list.add(new ErrorInfo("XML", "ERROR", "There is No Manager User"));
            }


        } else {
            list.add(new ErrorInfo("XML", "ERROR", "No user in server.xml"));
        }


        ByteBuffer buffer = c.allocate();
        // write header
        buffer = HEADER.write(buffer, c, true);
        // write fields
        for (FieldPacket field : FIELDS) {
            buffer = field.write(buffer, c, true);
        }

        buffer = EOF.write(buffer, c, true);
        // write rows
        byte packetId = EOF.getPacketId();

        for (ErrorInfo info : list) {
            RowDataPacket row = getRow(info, c.getCharset().getResults());
            row.setPacketId(++packetId);
            buffer = row.write(buffer, c, true);
        }

        EOFPacket lastEof = new EOFPacket();
        lastEof.setPacketId(++packetId);
        buffer = lastEof.write(buffer, c, true);
        c.write(buffer);

    }


    private static RowDataPacket getRow(ErrorInfo info, String charset) {
        RowDataPacket row = new RowDataPacket(FIELD_COUNT);
        row.add(StringUtil.encode(info.getType(), charset));
        row.add(StringUtil.encode(info.getLevel(), charset));
        row.add(StringUtil.encode(info.getDetail(), charset));
        return row;
    }

}
