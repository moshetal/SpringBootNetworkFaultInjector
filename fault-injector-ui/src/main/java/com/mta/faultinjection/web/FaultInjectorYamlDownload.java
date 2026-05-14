package com.mta.faultinjection.web;

/** YAML body plus suggested {@code Content-Disposition} filename. */
public record FaultInjectorYamlDownload(String body, String attachmentFilename) {}
