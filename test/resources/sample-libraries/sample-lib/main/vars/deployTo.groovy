// Sample shared library step — demonstrates a Map-argument library.

def call(Map params) {
    def envName = params.env ?: 'staging'
    def region = params.region ?: 'us-east-1'
    sh "deploy --env=${envName} --region=${region}"
}
