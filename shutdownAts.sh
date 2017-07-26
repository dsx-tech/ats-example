#!/usr/bin/env bash
kill $(jps | grep ats-1.0-SNAPSHOT.jar | awk '{print $1}')