{{- define "carrental.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "carrental.namespace" -}}
{{- .Release.Namespace }}
{{- end }}

{{- define "carrental.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: car-rental-service
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{- define "carrental.componentLabels" -}}
{{ include "carrental.labels" .root }}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app: {{ .name }}
{{- end }}

{{- define "carrental.selectorLabels" -}}
app: {{ .name }}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{- define "carrental.appImage" -}}
{{ printf "%s/%s-service:%s" .Values.app.imageRegistry .name .Values.app.imageTag }}
{{- end }}

{{- define "carrental.configMapName" -}}
{{ printf "%s-config" .name }}
{{- end }}

{{- define "carrental.secretName" -}}
{{ printf "%s-db-secret" .name }}
{{- end }}

{{- define "carrental.dbName" -}}
{{ printf "%s-db" .name }}
{{- end }}

{{- define "carrental.appName" -}}
{{ printf "%s-service" .name }}
{{- end }}

{{- define "carrental.pvcName" -}}
{{ printf "%s-db-pvc" .name }}
{{- end }}
