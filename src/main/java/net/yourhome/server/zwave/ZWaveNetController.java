/*-
 * Copyright (c) 2016 Coteq, Johan Cosemans
 * All rights reserved.
 *
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY COTEQ AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.yourhome.server.zwave;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.zwave4j.ValueGenre;
import org.zwave4j.ValueId;
import org.zwave4j.ValueType;

import net.yourhome.common.base.enums.ControllerTypes;
import net.yourhome.common.base.enums.GeneralCommands;
import net.yourhome.common.base.enums.ValueTypes;
import net.yourhome.common.base.enums.zwave.ZWaveCommandClassTypes;
import net.yourhome.common.net.messagestructures.JSONMessage;
import net.yourhome.common.net.messagestructures.general.ActivationMessage;
import net.yourhome.common.net.messagestructures.general.ClientMessageMessage;
import net.yourhome.common.net.messagestructures.general.SetValueMessage;
import net.yourhome.common.net.messagestructures.general.ValueChangedMessage;
import net.yourhome.common.net.messagestructures.general.ValueHistoryRequest;
import net.yourhome.common.net.messagestructures.zwave.ZWaveValue;
import net.yourhome.common.net.model.binding.ControlIdentifiers;
import net.yourhome.server.AbstractController;
import net.yourhome.server.ControllerNode;
import net.yourhome.server.ControllerValue;
import net.yourhome.server.IController;
import net.yourhome.server.base.DatabaseConnector;
import net.yourhome.server.base.Setting;
import net.yourhome.server.net.Server;

public class ZWaveNetController extends AbstractController {
	public enum Settings {
		ZWAVE_COM(
				new Setting("ZWAVE_COM", "USB port for Z-Wave USB key", "COM3 or /dev/ttyACM0")
		), ZWAVE_KEY(
				new Setting("ZWAVE_KEY", "ZWave Network Key", "1234567890123456")
		);
		private Setting setting;

		private Settings(Setting setting) {
			this.setting = setting;
		}

		public Setting get() {
			return this.setting;
		}
	}

	private final String VIRTUAL_VALUE_PREFIX = "virt_";
	private ZWaveController zwaveController;
	private Server netWebSocketServer;
	private DatabaseConnector dbconnector;
	private static volatile ZWaveNetController instance;
	private static Object lock = new Object();

	private ZWaveNetController() {
		this.log = Logger.getLogger("net.yourhome.server.zwave.ZWave");
		this.zwaveController = ZWaveController.getInstance();
		this.zwaveController.setZWaveNetController(this);
		this.dbconnector = DatabaseConnector.getInstance();
	}

	public static ZWaveNetController getInstance() {
		ZWaveNetController r = ZWaveNetController.instance;
		if (r == null) {
			synchronized (ZWaveNetController.lock) { // while we were waiting
														// for the lock, another
				r = ZWaveNetController.instance; // thread may have instantiated
				// the object
				if (r == null) {
					r = new ZWaveNetController();
					ZWaveNetController.instance = r;
				}
			}
		}
		return ZWaveNetController.instance;
	}

	@Override
	public JSONMessage parseNetMessage(JSONMessage message) {

		if (message instanceof SetValueMessage) {
			SetValueMessage message3 = (SetValueMessage) message;
			if (message3.controlIdentifiers.getValueIdentifier().startsWith(this.VIRTUAL_VALUE_PREFIX)) {
				this.getVirtualValues(
						this.zwaveController.getNodeFromIdentifier(message3.controlIdentifiers.getNodeIdentifier()));
				ControllerValue cachedValue = this.virtualValuesCache.get(message.controlIdentifiers.getKey());
				if (cachedValue != null) {
					if (cachedValue instanceof VirtualRGBWValue) {
						((VirtualRGBWValue) cachedValue).setColor(Color.decode(message3.value));
					}
				}
			} else {
				ZWaveValue zwaveValue = new ZWaveValue(message3.controlIdentifiers.getValueIdentifier());
				ValueId valueIdToBeChanged = this.zwaveController.setValue(zwaveValue.getHomeId(),
						zwaveValue.getNodeId(), zwaveValue.getInstance(), zwaveValue.getValueId(), message3.value);

				if (valueIdToBeChanged == null) {
					this.log.error("Value ID not found: " + message3.controlIdentifiers);
				}
			}
		} else if (message instanceof ActivationMessage) {
			// Check what is to be activated
			ActivationMessage activationMessage = (ActivationMessage) message;
			if (activationMessage.controlIdentifiers.getNodeIdentifier().equals("Scene")) {
				ClientMessageMessage returnMessage = new ClientMessageMessage();
				try {
					Short sceneId = Short.parseShort(activationMessage.controlIdentifiers.getValueIdentifier());
					String result = this.zwaveController.activateScene(sceneId);
					returnMessage.messageContent = result;
					returnMessage.broadcast = true;
				} catch (Exception e) {
					returnMessage.broadcast = false;
					returnMessage.messageContent = e.getMessage();
				}
				return returnMessage;
			} else if (activationMessage.controlIdentifiers.getNodeIdentifier().equals("General")) {
				if (activationMessage.controlIdentifiers.getValueIdentifier()
						.equals(GeneralCommands.ALL_OFF.convert())) {
					for (long homeId : this.getAllHomeIds()) {
						this.zwaveController.allOff(homeId);
					}
				} else if (activationMessage.controlIdentifiers.getValueIdentifier()
						.equals(GeneralCommands.ALL_ON.convert())) {
					for (long homeId : this.getAllHomeIds()) {
						this.zwaveController.allOn(homeId);
					}
				}
			}
		} else if (message instanceof ValueHistoryRequest) {
			// Let the general controller handle this one
			IController generalController = Server.getInstance().getControllers()
					.get(ControllerTypes.GENERAL.convert());
			if (generalController != null) {
				return generalController.parseNetMessage(message);
			}
		}

		/*
		 * if(message.type.equals("BasicCmd")) { BasicCmdMessage basicCmdMessage
		 * = new BasicCmdMessage(message); basicCmdMessage.commandClass =
		 * jsonObject.getInt("commandClass"); basicCmdMessage.nodeId = (short)
		 * ((Integer)jsonObject.getInt("nodeId")).intValue();
		 * basicCmdMessage.homeId = jsonObject.getLong("homeId");
		 * basicCmdMessage.value = jsonObject.getInt("value");
		 * this.zwaveController.setNodeBasic(basicCmdMessage.homeId,
		 * basicCmdMessage.nodeId, basicCmdMessage.value);
		 * 
		 * }else if(message.type.equals("SetValue")) { SetValueMessage message3
		 * = new SetValueMessage(message); message3.commandClass = (short)
		 * jsonObject.getInt("commandClass"); message3.nodeId = (short)
		 * ((Integer)jsonObject.getInt("nodeId")).intValue(); message3.homeId =
		 * jsonObject.getLong("homeId"); message3.valueId =
		 * BigInteger.valueOf(jsonObject.getLong("valueId")); message3.value =
		 * jsonObject.getString("value"); message3.instance = (short)
		 * jsonObject.getInt("instance");
		 * 
		 * ValueId valueIdToBeChanged =
		 * this.zwaveController.setValue(message3.homeId, message3.nodeId,
		 * message3.instance, message3.valueId, message3.value);
		 * if(valueIdToBeChanged != null) { JSONMessage mReturn =
		 * this.createValueChangedNetMessage(valueIdToBeChanged,message3.value);
		 * mReturn.broadcast = true; return mReturn; }else { log.error(
		 * "[zwave-Net] Value ID not found: "+message3.valueId); }
		 * 
		 * }else if(message.type.equals("SetScene")) {
		 * 
		 * SetSceneMessage setSceneMessage = new SetSceneMessage(message);
		 * setSceneMessage.sceneId = (short) jsonObject.getInt("sceneId"); try {
		 * setSceneMessage.broadcast = jsonObject.getBoolean("broadcast");
		 * }catch(Exception e) { setSceneMessage.broadcast = true; }
		 * 
		 * ZWaveScene scene; ClientMessageMessage returnMessage = new
		 * ClientMessageMessage(); try { String result =
		 * this.zwaveController.activateScene(setSceneMessage.sceneId); // scene
		 * = this.zwaveController.new ZWaveScene(setSceneMessage.sceneId,false);
		 * returnMessage.messageContent = result; } catch (Exception e) {
		 * returnMessage.broadcast = false; returnMessage.messageContent =
		 * e.getMessage(); } return returnMessage;
		 * 
		 * }else if(message.type.equals("GeneralCommand")) {
		 * GeneralCommandMessage message4 = new GeneralCommandMessage(message);
		 * message4.command = jsonObject.getString("command"); List<Long>
		 * homeIds = null; try { message4.homeId = jsonObject.getLong("homeId");
		 * if(message4.homeId == 0) { throw new Exception(); } homeIds = new
		 * ArrayList<Long>(); homeIds.add(message4.homeId); }catch(Exception e)
		 * { homeIds = this.getAllHomeIds(); }
		 * 
		 * for(Long homeId : homeIds) { log.debug("[zwave-Net] "
		 * +message4.command+" for homeid: " +homeId);
		 * 
		 * if(message4.command.equals("allOff")) {
		 * this.zwaveController.allOff(homeId);
		 * 
		 * // Addition: pause spotify or stop the radio //log.debug(
		 * "[zwave-Net] Stopping music"); //MusicPlayer.stopAllPlayers();
		 * 
		 * }else if(message4.command.equals("allOn")) {
		 * this.zwaveController.allOn(homeId); } } message4.broadcast = true;
		 * return message4;
		 * 
		 * } else if(message.type.equals("RequestSensorValuesMessage")) {
		 * IController generalController =
		 * NetWebSocketServer.getInstance().getControllers().get(ControllerTypes
		 * .GENERAL); if(generalController != null) { return
		 * generalController.parseNetMessage(jsonObject); } }else
		 * if(message.type.equals("SetColor")) { SetColorMessage setColorMessage
		 * = new SetColorMessage(message); setColorMessage.colorGreen =
		 * jsonObject.getInt("colorGreen"); setColorMessage.colorBlue =
		 * jsonObject.getInt("colorBlue"); setColorMessage.colorRed =
		 * jsonObject.getInt("colorRed"); //setColorMessage.colorWhite =
		 * jsonObject.getInt("colorGreen"); // Try to parse and set colors
		 * JSONObject colorValues = jsonObject.getJSONObject("colorValues");
		 * double colorValue; int colorValueInt;
		 * 
		 * // White try { ZWaveValue whiteValue = new
		 * ZWaveValue(colorValues.getJSONObject("white"));
		 * //this.zwaveController.setValue(whiteValue.getHomeId(),
		 * whiteValue.getNodeId(), whiteValue.getInstance(),
		 * whiteValue.getValueId(), setColorMessage.colorWhite) } catch
		 * (JSONException e) {} try { ZWaveValue redValue = new
		 * ZWaveValue(colorValues.getJSONObject("red")); colorValue =
		 * setColorMessage.colorRed/255.00*99.00; colorValueInt = (int)
		 * Math.round(colorValue);
		 * this.zwaveController.setValue(redValue.getHomeId(),
		 * redValue.getNodeId(), redValue.getInstance(), redValue.getValueId(),
		 * colorValue+""); } catch (JSONException e) {} try { ZWaveValue
		 * greenValue = new ZWaveValue(colorValues.getJSONObject("green"));
		 * colorValue = setColorMessage.colorGreen/255.00*99.00; colorValueInt =
		 * (int) Math.round(colorValue);
		 * this.zwaveController.setValue(greenValue.getHomeId(),
		 * greenValue.getNodeId(), greenValue.getInstance(),
		 * greenValue.getValueId(), colorValue+""); } catch (JSONException e) {}
		 * try { ZWaveValue blueValue = new
		 * ZWaveValue(colorValues.getJSONObject("blue")); colorValue =
		 * setColorMessage.colorBlue/255.00*99.00; colorValueInt = (int)
		 * Math.round(colorValue);
		 * this.zwaveController.setValue(blueValue.getHomeId(),
		 * blueValue.getNodeId(), blueValue.getInstance(),
		 * blueValue.getValueId(),colorValue+""); } catch (JSONException e) {} }
		 */
		return null;
	}

	public void ZWaveValueChanged(ValueId valueId) {
		if (!this.zwaveController.getValueId(valueId).equals(0)) {
			JSONMessage message = this.createValueChangedNetMessage(valueId);
			if (message != null) {
				// Send valuechange to all connected nodes
				this.netWebSocketServer.broadcast(message);

				this.triggerValueChanged(message.controlIdentifiers);

				// Save valuechange in db
				Object value = ZWaveController.getValueOfValue(valueId);
				Double valueDouble;
				switch (valueId.getType()) {
				case BOOL:
					valueDouble = ((Boolean) value) == true ? 1.0 : 0;
					break;
				default:
					valueDouble = Double.parseDouble(value.toString());
				}
				// this.dbconnector.insertValueChange(new
				// ControlIdentifiers(getIdentifier(),
				// ZWaveController.getNodeIdentifier(valueId.getNodeId(),
				// valueId.getHomeId()), new
				// ZWaveValue(valueId.getCommandClassId(), valueId.getNodeId(),
				// zwaveController.getValueId(valueId), valueId.getHomeId(),
				// valueId.getInstance()).toControlId()),
				// this.zwaveController.getValueUnits(valueId),
				// value.toString(), valueDouble);
				this.dbconnector.insertValueChange(message.controlIdentifiers,
						this.zwaveController.getValueUnits(valueId), value.toString(), valueDouble);
			}
		}
	}

	private JSONMessage createValueChangedNetMessage(ValueId valueId) {
		return this.createValueChangedNetMessage(valueId, null);
	}

	private JSONMessage createValueChangedNetMessage(ValueId valueId, String value) {
		ZWaveCommandClassTypes commandClass = ZWaveCommandClassTypes.fromByte((byte) (valueId.getCommandClassId()));
		ValueChangedMessage valueChangedMessage = new ValueChangedMessage();
		if (commandClass == null) {
			this.log.error("[ZWave] Command class not found: " + valueId.getCommandClassId());
			return null;
		} else {
			switch (commandClass) {
			// case Basic:
			case SwitchBinary:
			case SwitchMultilevel:
			case SensorMultiLevel:
			case Meter:
			case ThermostatSetpoint:
			case SensorAlarm:
			case SensorBinary:
			case SceneActivation:
			case SwitchToggleBinary:
			case SwitchToggleMultilevel:
			case Alarm:
			case Battery:
				Value valueDetails = this.zwaveController.getValue(valueId);
				if (valueDetails != null) {
					valueDetails.readProperties();
					if (valueDetails.getSubscribed()) {

						ControlIdentifiers identifiers = new ControlIdentifiers();
						identifiers.setControllerIdentifier(ControllerTypes.ZWAVE);
						identifiers.setValueIdentifier(valueDetails.getControlId());
						identifiers.setNodeIdentifier(
								ZWaveController.getNodeIdentifier(valueDetails.getNodeId(), valueDetails.getHomeId()));
						valueChangedMessage.controlIdentifiers = identifiers;
						valueChangedMessage.unit = valueDetails.getValueUnit();
						valueChangedMessage.value = valueDetails.getValue();
						valueChangedMessage.valueType = valueDetails.getValueTypeOfValue();
						if (valueChangedMessage.value != null && !valueChangedMessage.value.equals("null")) {
							return valueChangedMessage;
						}
					}
				}
				return null;
			default:
				return null;
			}
		}
	}

	public List<JSONMessage> getNodesInformation() {
		List<JSONMessage> messageList = new ArrayList<JSONMessage>();

		for (Node node : this.zwaveController.getNodeList()) {
			for (Value value : node.getValues()) {
				if (this.zwaveController.getValueId(value.getOriginalValueId()).intValue() != 0) {
					JSONMessage message = this.createValueChangedNetMessage(value.getOriginalValueId());
					if (message != null) {
						messageList.add(message);
					}
				}
			}
		}

		return messageList;
	}

	public List<Long> getAllHomeIds() {
		List<Long> homeIds = new ArrayList<Long>();
		for (Node node : this.zwaveController.getNodeList()) {
			if (!homeIds.contains(node.getHomeId()) && node.getHomeId() > 0) {
				homeIds.add(node.getHomeId());
			}
		}
		return homeIds;
	}

	@Override
	public String getIdentifier() {
		return ControllerTypes.ZWAVE.convert();
	}

	@Override
	public String getName() {
		return "ZWave";
	}

	@Override
	public void init() {
		super.init();
		this.netWebSocketServer = Server.getInstance();
		this.zwaveController.initialize();
	}

	@Override
	public List<JSONMessage> initClient() {
		return this.getNodesInformation();
	}

	@Override
	public boolean isEnabled() {
		return this.zwaveController.isEnabled();
	}

	@Override
	public List<ControllerNode> getNodes() {
		List<ControllerNode> returnList = new ArrayList<ControllerNode>();

		try {
			ControllerNode generalCommandsNode = new ControllerNode(this, GeneralCommands.getNodeIdentifier(),
					"General Commands", "");
			generalCommandsNode.addValue(
					new ControllerValue(GeneralCommands.ALL_OFF.convert(), "All off", ValueTypes.GENERAL_COMMAND));
			generalCommandsNode.addValue(
					new ControllerValue(GeneralCommands.ALL_ON.convert(), "All on", ValueTypes.GENERAL_COMMAND));
			returnList.add(generalCommandsNode);

			for (Node node : this.zwaveController.getNodeList()) {
				node.readProperties();
				ControllerNode commandsNode = new ControllerNode(this, node.getControlId(),
						node.getLabel().startsWith(node.getId() + ". ") ? node.getLabel()
								: node.getId() + ". " + node.getLabel(),
						this.getNodeType(node));
				for (Value value : node.getValues()) {
					value.readProperties();
					if (value.getOriginalValueId().getGenre() != ValueGenre.CONFIG && value.getSubscribed()) {
						commandsNode.addValue(new ControllerValue(value.getControlId(), value.getValueLabel(),
								value.getValueTypeOfValue()));
					}
				}
				List<ControllerValue> virtualValues = this.getVirtualValues(node);
				if (virtualValues != null) {
					for (ControllerValue virtualValue : virtualValues) {
						commandsNode.addValue(virtualValue);
					}
				}
				returnList.add(commandsNode);
			}
		} catch (Exception e) {
			this.log.error("Error: ", e);
		}
		return returnList;
	}

	private String getNodeType(Node node) {
		String type;
		if (!node.isAlive()) {
			type = "zwave-dead";
		} else {
			type = "";
		}
		return type;
	}

	@Override
	public String getValueName(ControlIdentifiers valueIdentifier) {
		Value value = this.zwaveController.getValue(valueIdentifier.getValueIdentifier());
		if (value != null) {
			ValueId valueId = value.getOriginalValueId();
			Value valueInfo = this.zwaveController.getValue(valueId);
			value.readProperties();
			if (valueInfo != null) {
				return valueInfo.getValueLabel();
			}
		}
		return "Unknown";
	}

	@Override
	public List<ControllerNode> getTriggers() {
		List<ControllerNode> allTriggers = this.getNodes();
		ControllerNode scenesNode = new ControllerNode(this, "Scenes", "Activation of ZWave Scene", "");
		for (int i = 1; i < 256; i++) {
			scenesNode.addValue(new ControllerValue(i + "", i + "", ValueTypes.SCENE_ACTIVATION));
		}
		allTriggers.add(scenesNode);
		return allTriggers;
	}

	@Override
	public String getValue(ControlIdentifiers valueIdentifiers) {
		Value value = this.zwaveController.getValue(valueIdentifiers.getValueIdentifier());
		return ZWaveController.getValueOfValue(value.getOriginalValueId()).toString();
	}

	@Override
	public boolean isInitialized() {
		return this.zwaveController.isNetworkInitialized();
	}

	@Override
	public List<Setting> getSettings() {
		List<Setting> returnList = new ArrayList<Setting>();
		for (Settings s : Settings.values()) {
			returnList.add(s.setting);
		}
		return returnList;
	}

	@Override
	public void destroy() {
		// this.zwaveController.destroy();
		ZWaveNetController.instance = null;
	}

	private Map<String, ControllerValue> virtualValuesCache = new HashMap<String, ControllerValue>();

	private List<ControllerValue> getVirtualValues(Node node) {
		List<ControllerValue> values = null;
		if ((node.getManufacturerId() != null && node.getManufacturerId().equals("0x010f") // FIBARO
																							// System
				&& node.getProductType() != null && node.getProductType().equals("0x0900") // FGRGBWM441
																							// RGBW
																							// Controller
				&& node.getProductId() != null && node.getProductId().equals("0x1000"))) {

			VirtualRGBWValue value = new VirtualRGBWValue("rgbw_color", "RGBW Color", ValueTypes.COLOR_BULB);
			ControlIdentifiers identifiers = new ControlIdentifiers(this.getIdentifier(), node.getControlId(),
					value.getIdentifier());
			VirtualRGBWValue cachedValue = (VirtualRGBWValue) this.virtualValuesCache.get(identifiers.getKey());
			if (values == null) {
				values = new ArrayList<>();
			}
			if (cachedValue == null) {
				// Add color bulb
				Value generalLevel = node.getValue(ValueType.BYTE, ValueGenre.USER, (short) 1, (short) 0);
				Value red = node.getValue(ValueType.BYTE, ValueGenre.USER, (short) 3, (short) 0);
				Value green = node.getValue(ValueType.BYTE, ValueGenre.USER, (short) 4, (short) 0);
				Value blue = node.getValue(ValueType.BYTE, ValueGenre.USER, (short) 5, (short) 0);
				Value white = node.getValue(ValueType.BYTE, ValueGenre.USER, (short) 6, (short) 0);

				if (generalLevel != null && white != null && red != null && green != null && blue != null) {
					value.setRed(red);
					value.setGreen(green);
					value.setBlue(blue);
					value.setWhite(white);
					value.setGeneralLevel(generalLevel);
					values.add(value);
					this.virtualValuesCache.put(identifiers.getKey(), value);
				}
			} else {
				values.add(cachedValue);
			}
		}
		return values;
	}

	class VirtualRGBWValue extends ControllerValue {
		private Value red;
		private Value green;
		private Value blue;
		private Value white;
		private Value generalLevel;

		public VirtualRGBWValue(String identifier, String name, ValueTypes valueType) {
			super(ZWaveNetController.this.VIRTUAL_VALUE_PREFIX + identifier, name, valueType);
			this.setVirtual(true);
		}

		public void setColor(Color color) {
			int redValue = (int) Math.round(color.getRed() / 255.00 * 99.00);
			ZWaveNetController.this.zwaveController.setValue(this.red.getHomeId(), this.red.getNodeId(),
					this.red.getInstance(), this.red.getValueId(), redValue + "");
			int greenValue = (int) Math.round(color.getGreen() / 255.00 * 99.00);
			ZWaveNetController.this.zwaveController.setValue(this.green.getHomeId(), this.green.getNodeId(),
					this.green.getInstance(), this.green.getValueId(), greenValue + "");
			int blueValue = (int) Math.round(color.getBlue() / 255.00 * 99.00);
			ZWaveNetController.this.zwaveController.setValue(this.blue.getHomeId(), this.blue.getNodeId(),
					this.blue.getInstance(), this.blue.getValueId(), blueValue + "");
			this.setBrightness(color);
		}

		public void setBrightness(Color color) {
			float brightnessValue = this.brightness(color.getRGB());
			ZWaveNetController.this.log.debug("brightness: " + brightnessValue);
			int blueValue = (int) Math.round(color.getBlue() * 99.00);
			ZWaveNetController.this.zwaveController.setValue(this.generalLevel.getHomeId(),
					this.generalLevel.getNodeId(), this.generalLevel.getInstance(), this.generalLevel.getValueId(),
					blueValue + "");
		}

		private float brightness(int color) {
			int r = color >> 16 & 255;
			int g = color >> 8 & 255;
			int b = color & 255;
			int V = Math.max(b, Math.max(r, g));
			return V / 255F;
		}

		/**
		 * @return the generalLevel
		 */
		public Value getGeneralLevel() {
			return this.generalLevel;
		}

		/**
		 * @param generalLevel
		 *            the generalLevel to set
		 */
		public void setGeneralLevel(Value generalLevel) {
			this.generalLevel = generalLevel;
		}

		/**
		 * @return the red
		 */
		public Value getRed() {
			return this.red;
		}

		/**
		 * @param red
		 *            the red to set
		 */
		public void setRed(Value red) {
			this.red = red;
		}

		/**
		 * @return the green
		 */
		public Value getGreen() {
			return this.green;
		}

		/**
		 * @param green
		 *            the green to set
		 */
		public void setGreen(Value green) {
			this.green = green;
		}

		/**
		 * @return the blue
		 */
		public Value getBlue() {
			return this.blue;
		}

		/**
		 * @param blue
		 *            the blue to set
		 */
		public void setBlue(Value blue) {
			this.blue = blue;
		}

		/**
		 * @return the white
		 */
		public Value getWhite() {
			return this.white;
		}

		/**
		 * @param white
		 *            the white to set
		 */
		public void setWhite(Value white) {
			this.white = white;
		}

	}
}