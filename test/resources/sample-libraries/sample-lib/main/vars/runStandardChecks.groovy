// Sample shared library step — demonstrates a no-arg library that
// uses multiple DSL globals.

def call() {
    sh 'lint'
    sh 'test'
    echo 'standard checks complete'
}
