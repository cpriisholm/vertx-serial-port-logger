#include <OneWire.h>
#include <EmonLib.h>

/* 
 * Production version
 *
 * Changed to use CSV rather than JSON, added checksum of CSV string as last part of logged data: 
 * 
 * Columns: PumpTemp,FeedTemp,ReturnTemp,BoardTemp,TankTemp,PumpL1Current,PumpL2Current,PumpL3Current,checksum
 * 
 * Note only L1 on the pump is measured asit draws the same amount from all three phases under normal operation.
 * It is only when the electrical heater element kicks in that L1 differs from the other two phases. 
 * From recorded data it seems that the heater element draws appr. 8.6 amps extra and L1 becomes larger than 14 amps 
 * - so on that case L2 and L3 are calculated to L1 - 8.6.
 * 
 * Checksum is done by xor'ing each byte in the string and then adding ",checksum" to the output. It is added to 
 * let consumer of the output see if complete set is received via serial port.
 */


///////////////////
// Configuration //
///////////////////

//
// Pins
//
const int leftGroupOneWirePin = 2; // Tank probe needs its own pin/bus probably due to long cabel versus short ones
const int rightGroupOneWirePin = 4; // The rest of the probes closer to the arduino

// It appears that all phases show the same usage so we just measure one phase and then tripple it
const int pumpCurrentPin = A5; // was pumpCurrentL1Pin
//const int burnerCurrentPin = A4; // not connected in box

const int powerLEDPin = 13;
const int alertLEDPin = 12;
const int burnerLEDPin = 8; // Connected in box but not in use
const int pumpLEDPin = 7;

// DS sensors on left group (left-most jack on front)
OneWire dsLeftGroup(leftGroupOneWirePin); 
byte owTankTempAddr[8]    = { 0x28, 0xD9, 0x4E, 0x65, 0x04, 0x00, 0x00, 0x88 };

// DS sensors fra right group (4 right-most jacks on front plus internal probe)
OneWire dsRightGroup(rightGroupOneWirePin); // was dsBryggers 
byte owFeedTempAddr[8]    = { 0x28, 0x6A, 0x75, 0x65, 0x04, 0x00, 0x00, 0x22 };
byte owReturnTempAddr[8]  = { 0x28, 0x65, 0x1F, 0x65, 0x04, 0x00, 0x00, 0xAA };
byte owPumpEnvTempAddr[8] = { 0x28, 0x10, 0x62, 0x65, 0x04, 0x00, 0x00, 0x58 };
byte owBoardTempAddr[8]   = { 0x28, 0x9D, 0x56, 0xA1, 0x04, 0x00, 0x00, 0x54 };
//byte owOutdoorTempAddr[8] = { 0x28, 0xEB, 0xC2, 0x65, 0x04, 0x00, 0x00, 0xE1 }; // connected in box but no in use

const float ERR_TEMP = -999.0; // anything below this value is considered an error indication
const double CURRENT_MIN = 0.5; // below that current value we consider it noise and do not turn on LED

EnergyMonitor emonPump;
//EnergyMonitor emonBurner;

// Log as close as possible to this interval (ms)
const long interval = 10000L;

//
// Setup
//
void setup(void) {
  Serial.begin(9600); // This is fast enough for our purposes
  while (!Serial) ; // wait for Arduino Serial Monitor
  
  // calibrated with amp meter on live setup 
  // (seems based on appr. 40mV/A which is measured and
  // quite different from the datasheet which state 25mV/A)
  emonPump.current(pumpCurrentPin, 40.75); 
  //emonBurner.current(burnerCurrentPin, 40.75);

  // LEDs
  pinMode(powerLEDPin, OUTPUT);
  pinMode(alertLEDPin, OUTPUT);
  pinMode(burnerLEDPin, OUTPUT);
  pinMode(pumpLEDPin, OUTPUT);
}

/////////////
// Program //
/////////////

// Tempature values
float tPump, tFeed, tReturn, tTank, tBoard; // tOut
// Current measurements
double cPump;
// Timestamps
long nextLog = interval;
long nextOneWireRead = interval-5000L; // read value some seconds before we log

boolean initialized = false;


//
// Loop
//
void loop(void) {
  
  // For the fun of it...
  if(!initialized) {
    animateInit();
    initialized = true;
  }
  
  long now = millis(); // record it as now
   
  // Normal operation
  if(millis() >= nextOneWireRead) {
    nextOneWireRead += interval;
    
    tTank = getTemperatureFromOneWire(dsLeftGroup, owTankTempAddr);
    //Serial.print("DBG: tTank="); Serial.println(tTank);

    // These only need to be read once per interval
    tBoard = getTemperatureFromOneWire(dsRightGroup, owBoardTempAddr);
    //Serial.print("DBG: tBoard="); Serial.println(tBoard);
    tPump = getTemperatureFromOneWire(dsRightGroup, owPumpEnvTempAddr);
    //Serial.print("DBG: tPump="); Serial.println(tPump);
    tFeed = getTemperatureFromOneWire(dsRightGroup, owFeedTempAddr);
    //Serial.print("DBG: tFeed="); Serial.println(tFeed);
    tReturn = getTemperatureFromOneWire(dsRightGroup, owReturnTempAddr);
    //Serial.print("DBG: tReturn="); Serial.println(tReturn);    
    //tOut = getTemperatureFromOneWire(dsRightGroup, owOutdoorTempAddr);
    //Serial.print("DBG: tOut="); Serial.println(tOut);

    
    cPump = emonPump.calcIrms(1480);
    //Serial.print("DBG: cPump="); Serial.println(cPump);
    
    // Update LEDs
    // Power is just high for now
    digitalWrite(powerLEDPin, HIGH); 
    // If any temp is error then blink
    if(tPump < ERR_TEMP || tFeed < ERR_TEMP || tReturn < ERR_TEMP || tTank < ERR_TEMP || tBoard < ERR_TEMP) {
      digitalWrite(alertLEDPin, HIGH);
    } else {
      digitalWrite(alertLEDPin, LOW);
    }
    // If current reading is higher than "noise" then turn on the led
    if(cPump >= CURRENT_MIN) {
      digitalWrite(pumpLEDPin, HIGH);
    } else {
      digitalWrite(pumpLEDPin, LOW);
    }
  }

  if(millis() >= nextLog) {
    nextLog += interval;

    // Sanitise values, there are outliers especially during startup
    tPump = (tPump < -99.0) ? -99.0 : (tPump > 100.0) ? 100.0 : tPump;
    tFeed = (tFeed < -99.0) ? -99.0 : (tFeed > 100.0) ? 100.0 : tFeed;
    tReturn = (tReturn < -99.0) ? -99.0 : (tReturn > 100.0) ? 100.0 : tReturn;
    tBoard = (tBoard < -99.0) ? -99.0 : (tBoard > 100.0) ? 100.0 : tBoard;
    tTank = (tTank < -99.0) ? -99.0 : (tTank > 100.0) ? 100.0 : tTank;
    cPump = (cPump < 0.0) ? 0.0 : (cPump > 20.0) ? 20.0 : cPump;

    float cPumpOthers = (cPump < 14.0) ? cPump : cPump - 8.6; // see comment at the top of the file
    
    /* This approach takes about 800-820 microseconds:
    String log = "";
    log += tPump;
    log += ",";
    ...*/
    /* This hits around 530 microseconds 
    char log[100];
    dtostrf(tPump, 3, 1, log);
    strcpy(&log[strlen(log)], ",");
    dtostrf(tFeed, 3, 1, &log[strlen(log)]);
    strcpy(&log[strlen(log)], ",");
    dtostrf(tReturn, 3, 1, &log[strlen(log)]);
    ...*/
    /* Sprintf approach takes about 4 microseconds */
    byte sum = 0;
    const int bufSize = 100;
    char buf[bufSize];
    char tPumpBuf[6]; // -99.0/100.0 + null
    char tFeedBuf[6];
    char tReturnBuf[6];
    char tBoardBuf[6];
    char tTankBuf[6];
    char cPumpBuf[5]; // 0.0/20.0 + null
    char cPumpOthersBuf[5];
    
    snprintf(buf, bufSize, "%s,%s,%s,%s,%s,%s,%s,%s", 
      dtostrf(tPump, 3, 1, tPumpBuf), 
      dtostrf(tFeed, 3, 1, tFeedBuf),
      dtostrf(tReturn, 3, 1, tReturnBuf),
      dtostrf(tBoard, 3, 1, tBoardBuf),
      dtostrf(tTank, 3, 1, tTankBuf),
      dtostrf(cPump, 3, 1, cPumpBuf),
      dtostrf(cPumpOthers, 3, 1, cPumpOthersBuf),
      dtostrf(cPumpOthers, 3, 1, cPumpOthersBuf));

    sum = crc8(buf, strlen(buf));
    
    Serial.print(buf);
    Serial.print(",");
    Serial.println(sum);
    Serial.flush();
  }
}

///////////////
// Utilities //
///////////////

/**
 * Matched by a similar crc on the java side of things
 */
uint8_t crc8(char* pointer, uint16_t len) {
    uint8_t CRC = 0x00;
    uint16_t tmp;

    while(len > 0) {
        tmp = CRC << 1;
        tmp += *pointer;
        CRC = (tmp & 0xFF) + (tmp >> 8);
        pointer++;
        --len;
    }

    return CRC;
}

/** 
 * For IT crowd-inspired blinking init routine 
 */
void animateInit() {
  const int blinkPattern[] = {1,0,0,0, 1,0,0,0, 1,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,1,0,1 };
  for(int i=0; i<=40; i++) {
    if (blinkPattern[i] == 1) {
      digitalWrite(powerLEDPin, HIGH);
      digitalWrite(alertLEDPin, HIGH);
    } else {
      digitalWrite(powerLEDPin, LOW);
      digitalWrite(alertLEDPin, LOW);
    }
    delay(200);
  }
}

/*
float getTemperatureFromAnalog(int pin) {
  // occasionally jumps 1/2 degree - better to do an average on it, also does not seem to be stable
   float temp_in_kelvin = analogRead(pin) * 0.0048828125 * 100; // 5 / 1024
   return temp_in_kelvin - 273.15  - 1.7; // somewhat random adjustment (calibrated to match digital out probe) 
}
*/

/** 
 * Return temperature in celcius from the device with given address
 * In case of error -1000.0 is returned (assuming that is a invalid value anyway)
 */
float getTemperatureFromOneWire(OneWire ds, byte deviceAddr[8]) {
  byte addr[8];
  byte data[12];
  boolean gotIt = false;
  byte present;
  float celsius;
//  int celsiusX10;
  // Not sure it is necessary doing the search given that there is a ds.select() but
  // it of course makes sure we can find it, and if not print some useful error to serial
  ds.reset_search();
  delay(250); // for some reason...
  while(ds.search(addr)) {
    /*Serial.print(Err);
    Serial.print("(addr=");
    printAddr(addr);
    Serial.print(")\n");*/
    if(isSameOneWireAddr(deviceAddr, addr)) {
      gotIt = true;
      break;
    }
  }
  
  if(!gotIt) {
    Serial.print("ERR: Adress not found (addr=");
    printAddr(deviceAddr);
    Serial.print(")\n");
    return -1000.0;
  }
  // Got it but is it useful
  if ( OneWire::crc8( deviceAddr, 7) != deviceAddr[7]) {
    Serial.print("ERR: CRC is not valid (addr=");
    printAddr(deviceAddr);
    Serial.print(")\n");
    return -1000.0;
  }
  // Is is a DS18B20 temperatur device at all
  if ( deviceAddr[0] != 0x28) {
    Serial.print("ERR: Device is not a DS18B20 family device (addr=");
    printAddr(deviceAddr);
    Serial.print(")\n");
    return -1000.0;
  }
  
  ds.reset();
  ds.select(addr);
  ds.write(0x44, 1);        // start conversion, with parasite power on at the end
  
  delay(1000);     // maybe 750ms is enough, maybe not
  // we might do a ds.depower() here, but the reset will take care of it.
  
  present = ds.reset(); // returns 0x01
  ds.select(deviceAddr);    
  ds.write(0xBE);         // Read Scratchpad
  for (int i = 0; i < 9; i++) {           // we need 9 bytes
    data[i] = ds.read();
  }
  int16_t raw = (data[1] << 8) | data[0];
  byte cfg = (data[4] & 0x60);
  // at lower res, the low bits are undefined, so let's zero them
  if (cfg == 0x00) raw = raw & ~7;  // 9 bit resolution, 93.75 ms
  else if (cfg == 0x20) raw = raw & ~3; // 10 bit res, 187.5 ms
  else if (cfg == 0x40) raw = raw & ~1; // 11 bit res, 375 ms
  //// default is 12 bit resolution, 750 ms conversion time
  
  celsius = (float)raw / 16.0;
  return celsius;
}

/** 
 * Compare two addresses 
 */
boolean isSameOneWireAddr(byte addr1[8], byte addr2[8]) {
   for(int i=0; i<8; i++) {
     if(addr1[i] != addr2[i]) {
       return false;
     }
   }
   return true;
}

/** 
 * Print address as hex 
 */
void printAddr(byte addr[8]) {
  for(int i = 0; i < 8; i++) {
    Serial.print(addr[i], HEX);
    Serial.print(" ");
  }
}
