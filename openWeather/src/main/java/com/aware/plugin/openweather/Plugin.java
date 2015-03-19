package com.aware.plugin.openweather;

import java.io.IOException;
import java.util.Locale;

import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.Locations;
import com.aware.plugin.openweather.Provider.OpenWeather_Data;
import com.aware.providers.Locations_Provider.Locations_Data;
import com.aware.utils.Aware_Plugin;
import com.aware.utils.Http;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

public class Plugin extends Aware_Plugin implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
	
	/**
	 * Shared context: new OpenWeather data is available
	 */
	public static final String ACTION_AWARE_PLUGIN_OPENWEATHER = "ACTION_AWARE_PLUGIN_OPENWEATHER";
	
	/**
	 * Extra string: openweather<br/>
	 * JSONObject from OpenWeather<br/>
	 * http://bugs.openweathermap.org/projects/api/wiki/Weather_Data
	 */
	public static final String EXTRA_OPENWEATHER = "openweather";
	
	private static final String OPENWEATHER_API_URL = "http://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&lang=%s&units=%s";
	private static ContextProducer sContextProducer;
	private static JSONObject sOpenWeather;

    private static GoogleApiClient mGoogleApiClient;

    private static LocationRequest locationRequest;
    private static Intent openWeatherIntent;
    private static PendingIntent openWeatherFetcher;
	
	@Override
	public void onCreate() {
		super.onCreate();

		Aware.startPlugin(this, getPackageName());

        if( Aware.getSetting(this, Settings.STATUS_PLUGIN_OPENWEATHER).length() == 0 ) {
            Aware.setSetting(this, Settings.STATUS_PLUGIN_OPENWEATHER, true);
        }
		if( Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER).length() == 0 ) {
			Aware.setSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER, "metric");
		}
        if( Aware.getSetting(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY).length() == 0 ) {
            Aware.setSetting(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY, 30);
        }

		CONTEXT_PRODUCER = new ContextProducer() {
			@Override
			public void onContext() {
				Intent mOpenWeather = new Intent(ACTION_AWARE_PLUGIN_OPENWEATHER);
				mOpenWeather.putExtra(EXTRA_OPENWEATHER, sOpenWeather.toString());
				sendBroadcast(mOpenWeather);
			}
		};
		sContextProducer = CONTEXT_PRODUCER;
		
		DATABASE_TABLES = Provider.DATABASE_TABLES;
		TABLES_FIELDS = Provider.TABLES_FIELDS;
		CONTEXT_URIS = new Uri[]{ OpenWeather_Data.CONTENT_URI };

        buildGoogleApiClient();

        locationRequest = new LocationRequest();
        locationRequest.setFastestInterval(1000);
        locationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);

        openWeatherIntent = new Intent(this, OpenWeather_Service.class);
        openWeatherFetcher = PendingIntent.getService(this, 0, openWeatherIntent, PendingIntent.FLAG_UPDATE_CURRENT);
	}

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        mGoogleApiClient.connect();
    }
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
        if( ! mGoogleApiClient.isConnecting() && ! mGoogleApiClient.isConnected() ) mGoogleApiClient.connect();

        TAG = "AWARE::OpenWeather";
        DEBUG = Aware.getSetting(getApplicationContext(), Aware_Preferences.DEBUG_FLAG).equals("true");

        if( DEBUG) Log.d(TAG,"Updating weather every " + Aware.getSetting(this, Settings.PLUGIN_OPENWEATHER_FREQUENCY) + " minute(s)");
        locationRequest.setInterval( Integer.valueOf(Aware.getSetting(this, Settings.PLUGIN_OPENWEATHER_FREQUENCY)) * 60 * 1000 ); //in minutes

        if(mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, openWeatherFetcher );
        }
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

        Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_LOCATION_NETWORK, false);
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, openWeatherFetcher);

        Aware.stopPlugin(this, getPackageName());
	}

    @Override
    public void onConnected(Bundle bundle) {
        Location lastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        if( lastLocation != null ) {
            openWeatherIntent.putExtra(LocationServices.FusedLocationApi.KEY_LOCATION_CHANGED, lastLocation);
            startService(openWeatherIntent);
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, openWeatherFetcher );
    }

    @Override
    public void onConnectionSuspended(int i) {
        if( ! mGoogleApiClient.isConnecting() ) mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {}

    /**
	 * Background service that will connect to OpenWeather API and fetch and store current weather conditions depending on the user's location
	 * @author dferreira
	 */
	public static class OpenWeather_Service extends IntentService {
		public OpenWeather_Service() {
			super("AWARE OpenWeather");
		}

		@Override
		protected void onHandleIntent(Intent intent) {
            Location location = (Location) intent.getExtras().get(LocationServices.FusedLocationApi.KEY_LOCATION_CHANGED);
            if( location == null ) return;

			double latitude = location.getLatitude();
			double longitude = location.getLongitude();
			
			if( latitude != 0 && longitude != 0 ) {
				Http httpObj = new Http();
				HttpResponse server_response = httpObj.dataGET(String.format(OPENWEATHER_API_URL, latitude, longitude, Locale.getDefault().getLanguage(), Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER)), false);
				if( server_response != null && server_response.getStatusLine().getStatusCode() == 200) {
					try {
						JSONObject raw_data = new JSONObject( EntityUtils.toString(server_response.getEntity()) );

                        if( DEBUG ) Log.d(Plugin.TAG,"OpenWeather answer: " + raw_data.toString(5));
						
						JSONObject wind = raw_data.getJSONObject("wind");
						JSONObject weather_characteristics = raw_data.getJSONObject("main");
						JSONObject weather = raw_data.getJSONArray("weather").getJSONObject(0);
						JSONObject clouds = raw_data.getJSONObject("clouds");

                        JSONObject rain = null;
                        if( raw_data.opt("rain") != null ) {
                            rain = raw_data.optJSONObject("rain");
                        }
                        JSONObject snow = null;
                        if( raw_data.opt("snow") != null ) {
                            snow = raw_data.optJSONObject("snow");
                        }
                        JSONObject sys = raw_data.getJSONObject("sys");
						
						ContentValues weather_data = new ContentValues();
						weather_data.put(OpenWeather_Data.TIMESTAMP, System.currentTimeMillis());
						weather_data.put(OpenWeather_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
						weather_data.put(OpenWeather_Data.CITY, raw_data.getString("name"));
						weather_data.put(OpenWeather_Data.TEMPERATURE, weather_characteristics.getDouble("temp"));
						weather_data.put(OpenWeather_Data.TEMPERATURE_MAX, weather_characteristics.getDouble("temp_max"));
						weather_data.put(OpenWeather_Data.TEMPERATURE_MIN, weather_characteristics.getDouble("temp_min"));
						weather_data.put(OpenWeather_Data.UNITS, Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER));
						weather_data.put(OpenWeather_Data.HUMIDITY, weather_characteristics.getDouble("humidity"));
						weather_data.put(OpenWeather_Data.PRESSURE, weather_characteristics.getDouble("pressure"));
						weather_data.put(OpenWeather_Data.WIND_SPEED, wind.getDouble("speed"));
						weather_data.put(OpenWeather_Data.WIND_DEGREES, wind.getDouble("deg"));
						weather_data.put(OpenWeather_Data.CLOUDINESS, clouds.getDouble("all"));

                        double rain_value = 0;
                        if( rain != null ) {
                            if (rain.opt("1h") != null) {
                                rain_value = rain.optDouble("1h", 0);
                            } else if (rain.opt("3h") != null) {
                                rain_value = rain.optDouble("3h", 0);
                            } else if (rain.opt("6h") != null) {
                                rain_value = rain.optDouble("6h", 0);
                            } else if (rain.opt("12h") != null) {
                                rain_value = rain.optDouble("12h", 0);
                            } else if (rain.opt("24h") != null) {
                                rain_value = rain.optDouble("24h", 0);
                            } else if (rain.opt("day") != null) {
                                rain_value = rain.optDouble("day", 0);
                            }
                        }

                        double snow_value = 0;
                        if( snow != null ) {
                            if (snow.opt("1h") != null) {
                                snow_value = snow.optDouble("1h", 0);
                            } else if (snow.opt("3h") != null) {
                                snow_value = snow.optDouble("3h", 0);
                            } else if (snow.opt("6h") != null) {
                                snow_value = snow.optDouble("6h", 0);
                            } else if (snow.opt("12h") != null) {
                                snow_value = snow.optDouble("12h", 0);
                            } else if (snow.opt("24h") != null) {
                                snow_value = snow.optDouble("24h", 0);
                            } else if (snow.opt("day") != null) {
                                snow_value = snow.optDouble("day", 0);
                            }
                        }
                        weather_data.put(OpenWeather_Data.RAIN, rain_value);
                        weather_data.put(OpenWeather_Data.SNOW, snow_value);
                        weather_data.put(OpenWeather_Data.SUNRISE, sys.getDouble("sunrise"));
                        weather_data.put(OpenWeather_Data.SUNSET, sys.getDouble("sunset"));
						weather_data.put(OpenWeather_Data.WEATHER_ICON_ID, weather.getInt("id"));
						weather_data.put(OpenWeather_Data.WEATHER_DESCRIPTION, weather.getString("main") + ": "+weather.getString("description"));
						
						getContentResolver().insert(OpenWeather_Data.CONTENT_URI, weather_data);
						
						sOpenWeather = raw_data;

						sContextProducer.onContext();
						
						if( DEBUG) Log.d(TAG, weather_data.toString());
						
					} catch (ParseException e) {
						if( DEBUG ) Log.d(TAG,"Error parsing JSON from server: " + e.getMessage());
					} catch (JSONException e) {
						if( DEBUG ) Log.d(TAG,"Error reading JSON: " + e.getMessage());
					} catch (IOException e) {
						if( DEBUG ) Log.d(TAG,"Error receiving JSON from server: " + e.getMessage());
					} catch( NullPointerException e ) {
                        if( DEBUG ) Log.d(TAG,"Failed to parse JSON from server:");
                        e.printStackTrace();
                    }
				}
			}
		}
	}
}