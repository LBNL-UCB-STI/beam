{{/* vim: set filetype=mustache: */}}
{{/* Expand the name of the chart. */}}

{{- define "beam-simulation.job" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-%s-%d-job" .Chart.Name $revision .Release.Time.Seconds | trunc 63 -}}
{{- end -}}

{{- define "beam-simulation.service" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-%s-%d-service" .Chart.Name $revision .Release.Time.Seconds | trunc 63 -}}
{{- end -}}
