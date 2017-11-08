--
-- PostgreSQL database dump
--

-- Dumped from database version 9.4.4
-- Dumped by pg_dump version 9.4.0
-- Started on 2017-02-21 18:15:22 PST


CREATE ROLE beam LOGIN
  NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE NOREPLICATION;

SET statement_timeout = 0;
SET lock_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;

--
-- TOC entry 2263 (class 1262 OID 106595)
-- Name: beam; Type: DATABASE; Schema: -; Owner: beam
--

CREATE DATABASE beam WITH TEMPLATE = template0 ENCODING = 'UTF8' LC_COLLATE = 'en_US.UTF-8' LC_CTYPE = 'en_US.UTF-8';


ALTER DATABASE beam OWNER TO beam;

\connect beam

SET statement_timeout = 0;
SET lock_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;

--
-- TOC entry 175 (class 3079 OID 12123)
-- Name: plpgsql; Type: EXTENSION; Schema: -; Owner: 
--

CREATE EXTENSION IF NOT EXISTS plpgsql WITH SCHEMA pg_catalog;


--
-- TOC entry 2266 (class 0 OID 0)
-- Dependencies: 175
-- Name: EXTENSION plpgsql; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION plpgsql IS 'PL/pgSQL procedural language';


SET search_path = public, pg_catalog;

SET default_tablespace = '';

SET default_with_oids = false;

--
-- TOC entry 174 (class 1259 OID 106596)
-- Name: trips; Type: TABLE; Schema: public; Owner: beam; Tablespace: 
--

CREATE TABLE trips (
    key text NOT NULL,
    trip bytea
);


ALTER TABLE trips OWNER TO beam;

--
-- TOC entry 2149 (class 2606 OID 106603)
-- Name: primarykey; Type: CONSTRAINT; Schema: public; Owner: beam; Tablespace: 
--

ALTER TABLE ONLY trips
    ADD CONSTRAINT primarykey PRIMARY KEY (key);


--
-- TOC entry 2265 (class 0 OID 0)
-- Dependencies: 8
-- Name: public; Type: ACL; Schema: -; Owner: beam
--

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM beam;
GRANT ALL ON SCHEMA public TO beam;
GRANT ALL ON SCHEMA public TO PUBLIC;


-- Completed on 2017-02-21 18:15:22 PST

--
-- PostgreSQL database dump complete
--

