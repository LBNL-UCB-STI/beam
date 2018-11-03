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
{{- printf "cluster-data" -}}
{{- end -}}

{{- define "beam-simulation.master.job" -}}
{{- $revision := htmlDate now -}}
{{- printf "master-job" -}}
{{- end -}}

{{- define "beam-simulation.master.job.container" -}}
{{- $revision := htmlDate now -}}
{{- printf "master-simulation-job" -}}
{{- end -}}

{{- define "beam-simulation.master.service" -}}
{{- $revision := htmlDate now -}}
{{- printf "master" -}}
{{- end -}}

{{- define "beam-simulation.worker.service" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker" -}}
{{- end -}}

{{- define "beam-simulation.worker.set" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-set" -}}
{{- end -}}

{{- define "beam-simulation.worker.job" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-job" -}}
{{- end -}}

{{- define "beam-simulation.worker.job.container" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-simulation-job" -}}
{{- end -}}


{{- define "beam-simulation.release_labels" }}
version: {{ .Chart.Version }}
release: {{ .Release.Name }}
app.kubernetes.io/name: {{ .Chart.Name | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
app.kubernetes.io/version: {{ .Chart.Version | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end }}

{{- define "beam-simulation.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 -}}
{{- end -}}
