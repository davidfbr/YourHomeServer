package net.yourhome.server.philipshue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.philips.lighting.hue.sdk.PHAccessPoint;
import com.philips.lighting.hue.sdk.PHBridgeSearchManager;
import com.philips.lighting.hue.sdk.PHHueSDK;
import com.philips.lighting.hue.sdk.PHMessageType;
import com.philips.lighting.hue.sdk.PHSDKListener;
import com.philips.lighting.hue.sdk.utilities.PHUtilities;
import com.philips.lighting.hue.sdk.utilities.impl.Color;
import com.philips.lighting.model.PHBridge;
import com.philips.lighting.model.PHLight;
import com.philips.lighting.model.PHLightState;

import net.yourhome.common.base.enums.ControllerTypes;
import net.yourhome.common.base.enums.GeneralCommands;
import net.yourhome.common.base.enums.ValueTypes;
import net.yourhome.common.net.messagestructures.JSONMessage;
import net.yourhome.common.net.messagestructures.general.ActivationMessage;
import net.yourhome.common.net.messagestructures.general.SetValueMessage;
import net.yourhome.common.net.messagestructures.general.ValueChangedMessage;
import net.yourhome.common.net.model.binding.ControlIdentifiers;
import net.yourhome.server.AbstractController;
import net.yourhome.server.ControllerNode;
import net.yourhome.server.ControllerValue;
import net.yourhome.server.base.DatabaseConnector;
import net.yourhome.server.base.GeneralController;
import net.yourhome.server.base.Setting;
import net.yourhome.server.base.SettingsManager;
import net.yourhome.server.net.Server;

public class HueController extends AbstractController {
	public enum Settings {

		PHILIPS_HUE_BRIDGE_IP(new Setting("PHILIPS_HUE_BRIDGE", "Bridge IP address", "")),
		PHILIPS_HUE_USERNAME(new Setting("PHILIPS_HUE_USERNAME", "Bridge Username", "Auto generated after setup")); 

		private Setting setting;

		private Settings(Setting setting) {
			this.setting = setting;
		}

		public Setting get() {
			return setting;
		}
	}
	private String username;
	private String bridgeIp;
	private PHHueSDK phHueSDK;
	private PHBridge bridge = null;
	private Map<String, ValueChangedMessage> values = new HashMap<String, ValueChangedMessage>();
	
	private static volatile HueController instance;
	private static Object lock = new Object();
	private HueController() {
		log = Logger.getLogger("net.yourhome.server.HueController");
	}
	public static HueController getInstance() {
		HueController r = instance;
		if (r == null) {
			synchronized (lock) { // while we were waiting for the lock, another
				r = instance; // thread may have instantiated
								// the object
				if (r == null) {
					r = new HueController();
					instance = r;
				}
			}
		}
		return instance;
	}
	@Override
	public String getIdentifier() {
		return ControllerTypes.PHILIPS_HUE.convert();
	}
	@Override
	public String getName() {
		return "Philips Hue";
	}
	@Override
	public String getValueName(ControlIdentifiers valueIdentifiers) {
		List<PHLight> lights = bridge.getResourceCache().getAllLights();
		if(lights != null) {
			for(PHLight light : lights) {
				if(light.getIdentifier().equals(valueIdentifiers.getValueIdentifier())) {
					return light.getName();	
				}
			}
		}
		return "";
	}
	@Override
	public String getValue(ControlIdentifiers valueIdentifiers) {
		return this.values.get(valueIdentifiers.getValueIdentifier()).value;
	}
	@Override
	public List<JSONMessage> initClient() {
		return new ArrayList<JSONMessage>(this.values.values());
	}
	@Override
	public boolean isEnabled() {
		return bridge!=null;
	}
	@Override
	public boolean isInitialized() {
		return bridge!=null;
	}
	@Override
	public JSONMessage parseNetMessage(JSONMessage message) {
		if (message instanceof ActivationMessage) {
			if(message.controlIdentifiers.getValueIdentifier().equals(GeneralCommands.ALL_OFF.convert())) {
				allOnOff(false);
			}else if(message.controlIdentifiers.getValueIdentifier().equals(GeneralCommands.ALL_ON.convert())) {
				allOnOff(true);
			}
		}else if (message instanceof SetValueMessage) {
			int color = valueToColor(((SetValueMessage)message).value);
			PHLight lightToBeChanged = bridge.getResourceCache().getLights().get(message.controlIdentifiers.getValueIdentifier());
			
			if(color == Color.BLACK || color == Color.TRANSPARENT) {
				setPower(lightToBeChanged, false);
			}else {
				setPower(lightToBeChanged, true);
				setColor(lightToBeChanged, color);
			}
		}
		return null;
	}
	@Override
	public List<ControllerNode> getNodes() {
		List<ControllerNode> allNodes = new ArrayList<ControllerNode>();

		if(bridge != null) {
			List<PHLight> lights = bridge.getResourceCache().getAllLights();
			if(lights != null) {
				ControllerNode lightsNode = new ControllerNode(this, "Lights", "Lights", "");
				allNodes.add(lightsNode);
				for(PHLight light : lights) {
					ControllerValue lightValue = new ControllerValue(light.getIdentifier(),light.getName(),ValueTypes.COLOR_BULB);
					lightsNode.addValue(lightValue);	
				}
				
				ControllerNode generalCommandsNode = new ControllerNode(this, GeneralCommands.getNodeIdentifier(), "General Commands", "");
				generalCommandsNode.addValue(new ControllerValue(GeneralCommands.ALL_OFF.convert(), "All off", ValueTypes.GENERAL_COMMAND));
				generalCommandsNode.addValue(new ControllerValue(GeneralCommands.ALL_ON.convert(), "All on", ValueTypes.GENERAL_COMMAND));
				allNodes.add(generalCommandsNode);
			}
		}
		return allNodes;
	}
	@Override
	public List<ControllerNode> getTriggers() {
		// TODO Auto-generated method stub
		return null;
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
	public void init() {
		super.init();
		log.debug("Initializing Hue");

		// Connect to bridge!
		phHueSDK = PHHueSDK.getInstance();
		phHueSDK.setAppName("YourHome Server");
		phHueSDK.setDeviceName(GeneralController.Settings.SERVER_NAME.get().convert());
	    phHueSDK.getNotificationManager().registerSDKListener(listener);
	    
	    username = SettingsManager.getStringValue(getIdentifier(), Settings.PHILIPS_HUE_USERNAME.get());
	    bridgeIp = SettingsManager.getStringValue(getIdentifier(), Settings.PHILIPS_HUE_BRIDGE_IP.get());
	    
	    if(username != null && !username.equals("")
	    		&& bridgeIp != null && !bridgeIp.equals("")) {
	    	connect();
	    }else if ((username == null || username.equals(""))
	    		&& bridgeIp != null && !bridgeIp.equals("")) {
	    	connect();
	    }else {
			log.debug("Searching for Hue bridges in your network");
	        PHBridgeSearchManager sm = (PHBridgeSearchManager) phHueSDK.getSDKService(PHHueSDK.SEARCH_BRIDGE);
	        sm.search(true, true); 
	    }
	}
	@Override
	public void destroy() {
		super.destroy();
		if(phHueSDK != null) {
			phHueSDK.disableAllHeartbeat();
		}
	}
	private void connect() {
	    if(username==null || username.equals("")) {
	    	username = "123";
		    log.debug("Connecting to "+bridgeIp+" without username.");	
	    }else {
			log.debug("Connecting to "+bridgeIp+" with username "+username);	
	    }
	    PHAccessPoint accessPoint = new PHAccessPoint();
	    accessPoint.setIpAddress(bridgeIp);
	    accessPoint.setUsername(username);
	    phHueSDK.connect(accessPoint);
	}
	private void updateLights() {
		for(PHLight light : bridge.getResourceCache().getAllLights()) {
			updateValue(light.getIdentifier(),light);
		}
	}
	private void updateValue(String valueIdentifier, PHLight light) {
		ValueChangedMessage valueChanged = this.values.get(valueIdentifier);
		int intColor = PHUtilities.colorFromXY(new float[] { light.getLastKnownLightState().getX(), light.getLastKnownLightState().getY() }, light.getModelNumber());
//		String formattedValue = String.format("#%06X", (0xFFFFFF & intColor));
		String formattedValue = colorToValue(intColor);
		boolean changed = false;
		if (valueChanged == null) {
			valueChanged = new ValueChangedMessage();
			valueChanged.broadcast = true;
			valueChanged.controlIdentifiers = new ControlIdentifiers(getIdentifier(), "Lights", valueIdentifier);
			valueChanged.unit = "";
			valueChanged.value = formattedValue;
			valueChanged.valueType = ValueTypes.COLOR_BULB;
			this.values.put(valueIdentifier, valueChanged);
			changed = true;
		} else {
			if (!formattedValue.equals(valueChanged.value)) {
				changed = true;
				valueChanged.value = formattedValue;
			}
		}
		log.debug("Value: " + valueIdentifier + ": " + formattedValue);
		if (changed) {
			valueChanged.value = formattedValue;

			DatabaseConnector.getInstance().insertValueChange(valueChanged.controlIdentifiers, valueChanged.unit, valueChanged.value, 0.0);

			Server.getInstance().broadcast(valueChanged);

			this.triggerValueChanged(valueChanged.controlIdentifiers);

			log.debug("Value change: " + valueIdentifier + ": " + formattedValue);
		}
	}
	private void writeLights() {
		List<PHLight> lights = bridge.getResourceCache().getAllLights();
		if(lights != null) {
			for(PHLight light : lights) {
				log.info("Found light: "+light.getName()+" / "+light.getIdentifier());
			}
		}
	}
	private String colorToValue(int color) {
		return color+"";
	}
	private int valueToColor(String value) {
		int result = 0;
		try {
			result = Integer.parseInt(value);
		}catch(NumberFormatException e) {
			result = Color.parseColor(value);
		}
		return result;
	}
	private void allOnOff(boolean on) {
		if(bridge != null) {
			List<PHLight> lights = bridge.getResourceCache().getAllLights();
			for(PHLight light : lights) {
				this.setPower(light, on);
			}
		}
	}
	private boolean setColor(PHLight light, int color) {
        if (bridge == null) {
            return false;
        }
        float[] xy = PHUtilities.calculateXYFromRGB(Color.red(color), Color.green(color), Color.blue(color), light.getModelNumber());
        PHLightState state = new PHLightState();
        state.setX(xy[0]);
        state.setY(xy[1]);
        state.setEffectMode(PHLight.PHLightEffectMode.EFFECT_NONE);
        state.setAlertMode(PHLight.PHLightAlertMode.ALERT_NONE);
        bridge.updateLightState(light, state);
        
        
        
        int brightness = (int) Math.round(Color.brightness(color)*254.0);
        setBrightness(light,brightness);
        
        return true;
    }
	public boolean setBrightness(PHLight light, int brightness) {
		if (bridge == null) {
		    return false;
		}
		
		PHLightState state = new PHLightState();
		state.setBrightness(brightness);
		state.setEffectMode(PHLight.PHLightEffectMode.EFFECT_NONE);
		state.setAlertMode(PHLight.PHLightAlertMode.ALERT_NONE);
		bridge.updateLightState(light, state);
		return true;
	}
    public boolean setPower(PHLight light, boolean power) {
		if (bridge == null) {
		    return false;
		}
		
		PHLightState state = new PHLightState();
		state.setOn(power);
		state.setEffectMode(PHLight.PHLightEffectMode.EFFECT_NONE);
		state.setAlertMode(PHLight.PHLightAlertMode.ALERT_NONE);
		bridge.updateLightState(light, state);
		return true;
    }

	// Local SDK Listener
    private PHSDKListener listener = new PHSDKListener() {

        @Override
        public void onAccessPointsFound(List accessPoint) {
             // Handle your bridge search results here.  Typically if multiple results are returned you will want to display them in a list 
             // and let the user select their bridge.   If one is found you may opt to connect automatically to that bridge.
    		for(Object ap : accessPoint) {        
    			if(ap instanceof PHAccessPoint) {
    				log.info("Found hue bridge: "+((PHAccessPoint)ap).getIpAddress()+", username "+((PHAccessPoint)ap).getUsername());
    			}
    		}
    		if(accessPoint.size()>0) {
    			if(accessPoint.get(0) instanceof PHAccessPoint) {
    				PHAccessPoint firstAccessPoint = ((PHAccessPoint)accessPoint.get(0));
    				username = firstAccessPoint.getUsername();
    				bridgeIp = firstAccessPoint.getIpAddress();
    				
    				SettingsManager.setStringValue(getIdentifier(), Settings.PHILIPS_HUE_BRIDGE_IP.get().getName(), bridgeIp);
    				if(username != null && !username.equals("")) {
    					SettingsManager.setStringValue(getIdentifier(), Settings.PHILIPS_HUE_USERNAME.get().getName(), username);
    				}
					SettingsManager.storeSettings();
    				connect();
    			}
    		}
        }
        
        @Override
        public void onCacheUpdated(List cacheNotificationsList, PHBridge bridge) {
    		log.debug("onCacheUpdated");
             // Here you receive notifications that the BridgeResource Cache was updated. Use the PHMessageType to   
             // check which cache was updated, e.g.
            if (cacheNotificationsList.contains(PHMessageType.LIGHTS_CACHE_UPDATED)) {
               updateLights();
            }
        }

        @Override
        public void onBridgeConnected(PHBridge connectedBridge, String user) {
            phHueSDK.setSelectedBridge(connectedBridge);
            phHueSDK.enableHeartbeat(connectedBridge, PHHueSDK.HB_INTERVAL);
            
            bridge = connectedBridge;
            username = user;
            SettingsManager.setStringValue(getIdentifier(), Settings.PHILIPS_HUE_BRIDGE_IP.get().getName(), bridgeIp);
			SettingsManager.setStringValue(getIdentifier(), Settings.PHILIPS_HUE_USERNAME.get().getName(), username);
			SettingsManager.storeSettings();

			log.info("Connected to "+bridgeIp+"/"+username);
            writeLights(); 
        }

        @Override
        public void onAuthenticationRequired(PHAccessPoint accessPoint) {
            phHueSDK.startPushlinkAuthentication(accessPoint);
    		log.info("Push the button on your Hue bridge within 30 seconds.");
        }

        @Override
        public void onConnectionResumed(PHBridge bridge) {
    		//log.debug("onConnectionResumed");
        }

        @Override
        public void onConnectionLost(PHAccessPoint accessPoint) {
    		log.debug("onConnectionLost");
    		if(phHueSDK != null) {
    			phHueSDK.disableHeartbeat(bridge);
    		}
        }
        
        @Override
        public void onError(int code, final String message) {
        	switch(code) {
	        	case 101:
	    			log.info("Please push the link button on the Hue bridge now.");
	        		break;
	        	case 1157:
	    			log.info("No Hue bridges found in your network. You can still add one manually in the server settings.");
	        		break;
	    		default:
	        		log.error("Hue error "+code+": "+message);
	        		break;
        	}
        }

        @Override
        public void onParsingErrors(List parsingErrorsList) {
    		log.debug("onParsingErrors");
            // Any JSON parsing errors are returned here.  Typically your program should never return these.      
        }
    };
}
