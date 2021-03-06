create table if not exists raw_stats(
        id integer generated by default as identity (start with 1) primary key,
        ap varchar(35) not null,
        tstamp timestamp with time zone default current_timestamp,
        direction varchar(8) not null,
        sender varchar(35) not null,
        receiver varchar(35) not null,
        doc_type varchar(255) not null,
        profile varchar(255) ,
        channel varchar(255)
);
