{{/* vim: set filetype=mustache: */}}
{{/* Expand the name of the chart. */}}

{{- define "beam-simulation.namespace" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-%s-%d" .Chart.Name $revision .Release.Time.Seconds | trunc 63 -}}
{{- end -}}

{{- define "beam-simulation.resources" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-%s-%d-mem-cpu" .Chart.Name $revision .Release.Time.Seconds | trunc 63 -}}
{{- end -}}

{{- define "beam-simulation.secret" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-%s-%d-secret" .Chart.Name $revision .Release.Time.Seconds | trunc 63 -}}
{{- end -}}

{{- define "beam-simulation.data" -}}
{{- $revision := htmlDate now -}}
{{- printf "%s-data" .Chart.Name | trunc 63 -}}
{{- end -}}

{{- define "beam-simulation.release_labels" }}
version: {{ .Chart.Version }}
release: {{ .Release.Name }}
app.kubernetes.io/name: {{ .Chart.Name | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
app.kubernetes.io/version: {{ .Chart.Version | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
app.kubernetes.io/time: {{ .Values.global.creation | quote }}
{{- end }}

{{- define "beam-simulation.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 -}}
{{- end -}}
