/*
 * Copyright 2018 - 2019 Anton Tananaev (anton@traccar.org)
 *
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
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class ItsProtocolDecoder extends BaseProtocolDecoder {

    public ItsProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .expression("[^$]*")
            .text("$")
            .expression(",?[^,]+,")              // event
            .groupBegin()
            .expression("[^,]+,")                // vendor
            .expression("[^,]+,")                // firmware version
            .expression("(..),")                 // status
            .number("(d+),")                     // event
            .expression("([LH]),")               // history
            .or()
            .expression("([^,]+),")              // type
            .groupEnd()
            .number("(d{15}),")                  // imei
            .groupBegin()
            .expression("(..),")                 // status
            .or()
            .expression("[^,]*,")                // vehicle registration
            .number("([01]),")                   // valid
            .groupEnd()
            .number("(dd),?(dd),?(dddd),")       // date (ddmmyyyy)
            .number("(dd),?(dd),?(dd),")         // time (hhmmss)
            .expression("([AV]),").optional()    // valid
            .number("(d+.d+),([NS]),")           // latitude
            .number("(d+.d+),([EW]),")           // longitude
            .groupBegin()
            .number("(d+.?d*),")                 // speed
            .number("(d+.?d*),")                 // course
            .number("(d+),")                     // satellites
            .groupBegin()
            .number("(d+.?d*),")                 // altitude
            .number("d+.?d*,")                   // pdop
            .number("d+.?d*,")                   // hdop
            .expression("[^,]*,")
            .number("([01]),")                   // ignition
            .number("([01]),")                   // charging
            .number("(d+.?d*),")                 // power
            .number("(d+.?d*),")                 // battery
            .number("([01]),")                   // emergency
            .expression("[CO]?,")                // tamper
            .number("((?:x+,){5}")               // main cell
            .number("(?:-?x+,){12})")            // other cells
            .number("([01]{4}),")                // inputs
            .number("([01]{2}),")                // outputs
            .groupBegin()
            .number("d+,")                       // index
            .number("(d+.d+),")                  // adc1
            .number("(d+.d+),")                  // adc2
            .groupEnd("?")
            .groupEnd("?")
            .or()
            .number("(-?d+.d+),")                // altitude
            .number("(d+.d+),")                  // speed
            .groupEnd()
            .any()
            .compile();

    private String decodeAlarm(String status) {
        switch (status) {
            case "WD":
            case "EA":
                return Position.ALARM_SOS;
            case "BL":
                return Position.ALARM_LOW_BATTERY;
            case "HB":
                return Position.ALARM_BRAKING;
            case "HA":
                return Position.ALARM_ACCELERATION;
            case "RT":
                return Position.ALARM_CORNERING;
            case "OS":
                return Position.ALARM_OVERSPEED;
            case "TA":
                return Position.ALARM_TAMPERING;
            default:
                return null;
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;

        if (channel != null && sentence.startsWith("$,01,")) {
            channel.writeAndFlush(new NetworkMessage("$,1,*", remoteAddress));
        }

        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        String status = parser.next();
        Integer event = parser.nextInt();
        boolean history = "H".equals(parser.next());
        String type = parser.next();

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (type != null && type.equals("EMR")) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        }

        if (event != null) {
            position.set(Position.KEY_EVENT, event);
        }
        if (history) {
            position.set(Position.KEY_ARCHIVE, true);
        }

        if (parser.hasNext()) {
            status = parser.next();
        }
        if (status != null) {
            position.set(Position.KEY_ALARM, decodeAlarm(status));
        }

        if (parser.hasNext()) {
            position.setValid(parser.nextInt() == 1);
        }
        position.setTime(parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS));
        if (parser.hasNext()) {
            position.setValid(parser.next().equals("A"));
        }
        position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_HEM));
        position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_HEM));

        if (parser.hasNext(3)) {
            position.setSpeed(UnitsConverter.knotsFromKph(parser.nextDouble()));
            position.setCourse(parser.nextDouble());
            position.set(Position.KEY_SATELLITES, parser.nextInt());
        }

        if (parser.hasNext(8)) {
            position.setAltitude(parser.nextDouble());
            position.set(Position.KEY_IGNITION, parser.nextInt() > 0);
            position.set(Position.KEY_CHARGE, parser.nextInt() > 0);
            position.set(Position.KEY_POWER, parser.nextDouble());
            position.set(Position.KEY_BATTERY, parser.nextDouble());

            position.set("emergency", parser.nextInt() > 0);

            String[] cells = parser.next().split(",");
            int mcc = Integer.parseInt(cells[1]);
            int mnc = Integer.parseInt(cells[2]);
            int lac = Integer.parseInt(cells[3], 16);
            int cid = Integer.parseInt(cells[4], 16);
            Network network = new Network(CellTower.from(mcc, mnc, lac, cid, Integer.parseInt(cells[0])));
            for (int i = 0; i < 4; i++) {
                lac = Integer.parseInt(cells[5 + 3 * i + 1], 16);
                cid = Integer.parseInt(cells[5 + 3 * i + 2], 16);
                if (lac > 0 && cid > 0) {
                    network.addCellTower(CellTower.from(mcc, mnc, lac, cid));
                }
            }
            position.setNetwork(network);

            position.set(Position.KEY_INPUT, parser.nextBinInt());
            position.set(Position.KEY_OUTPUT, parser.nextBinInt());
        }

        if (parser.hasNext(2)) {
            position.set(Position.PREFIX_ADC + 1, parser.nextDouble());
            position.set(Position.PREFIX_ADC + 2, parser.nextDouble());
        }

        if (parser.hasNext(2)) {
            position.setAltitude(parser.nextDouble());
            position.setSpeed(UnitsConverter.knotsFromKph(parser.nextDouble()));
        }

        return position;
    }

}
