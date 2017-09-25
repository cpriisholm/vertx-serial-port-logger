create table pump_temperature (
  ts      timestamp default current_timestamp not null,
  sensor  varchar(4) not null,
  data    float not null,
  primary key (ts,sensor)
);
create table pump_current (
  ts      timestamp default current_timestamp not null,
  sensor  varchar(4) not null,
  data    float not null,
  primary key (ts,sensor)
);
